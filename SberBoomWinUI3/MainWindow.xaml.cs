using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Windowing;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using SberBoom.WinUI.Data;
using SberBoom.WinUI.Net;
using SberBoom.WinUI.Protocol;
using Windows.Graphics;
using Windows.UI;

namespace SberBoom.WinUI;

/// <summary>
/// Окно в стиле Fluent Design System: Mica, штатные контролы WinUI 3, системный
/// акцент и типографика Windows. Функционально повторяет Android/Compose-версию:
/// плеер, очередь, текст песни, Bluetooth, история, диагностика, таймер сна,
/// поиск и каталог Звука, сопряжение PIN-токеном.
/// </summary>
public sealed partial class MainWindow : Window
{
    private const string ClientName = Settings.DefaultClientName;

    private readonly Settings _settings = Settings.Load();
    private readonly ZvukApi _zvuk = new();
    private readonly DispatcherTimer _refreshTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly DispatcherTimer _keepAliveTimer = new() { Interval = TimeSpan.FromSeconds(20) };
    private readonly DispatcherTimer _tickTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly DispatcherTimer _seekTimer = new() { Interval = TimeSpan.FromMilliseconds(450) };

    private SpeakerClient? _client;
    private SpeakerState? _state;
    private string? _coverUrl;
    private bool _updatingUi;
    private bool _seekPending;
    private int _seekTarget;
    private bool _shuffle;
    private string _repeat = "none";
    private bool _liked;
    private float _speed = 1f;
    private string? _lyricsFor;
    private List<LrcLine> _lyrics = new();
    private bool _sleepFired;

    public MainWindow()
    {
        InitializeComponent();
        Title = "SberBoom Remote";
        ResizeWindow(480, 920);

        // Fluent: Mica-подложка и собственный заголовок окна.
        // Если Mica в системе не поддерживается, корень получает непрозрачный фон —
        // иначе прозрачное окно выглядит пустым.
        try
        {
            if (MicaController.IsSupported())
            {
                SystemBackdrop = new MicaBackdrop { Kind = MicaKind.BaseAlt };
            }
            else
            {
                RootGrid.Background = (Brush)Application.Current.Resources["SolidBackgroundFillColorBaseBrush"];
                Program.Log("mica unsupported — solid background");
            }
        }
        catch (Exception ex)
        {
            Program.Log("mica: " + ex.Message);
            RootGrid.Background = (Brush)Application.Current.Resources["SolidBackgroundFillColorBaseBrush"];
        }

        try
        {
            ExtendsContentIntoTitleBar = true;
            SetTitleBar(TitleBarDragArea);
        }
        catch (Exception ex)
        {
            Program.Log("titlebar: " + ex.Message);
        }

        ApplyTheme();

        _refreshTimer.Tick += (_, _) => _ = RefreshAsync();
        _keepAliveTimer.Tick += (_, _) => _ = KeepAliveAsync();
        _tickTimer.Tick += (_, _) => OnTick();
        _seekTimer.Tick += (_, _) => FlushSeek();

        HostBox.Text = _settings.Host ?? string.Empty;
        Program.Log("window ready");

        _ = StartupAsync();
    }

    private void ResizeWindow(int width, int height)
    {
        try
        {
            AppWindow.Resize(new SizeInt32(width, height));
            var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Nearest);
            var x = area.WorkArea.X + (area.WorkArea.Width - width) / 2;
            var y = area.WorkArea.Y + Math.Max(0, (area.WorkArea.Height - height) / 2);
            AppWindow.Move(new PointInt32(x, y));
        }
        catch (Exception ex)
        {
            Program.Log("resize failed: " + ex.Message);
        }
    }

    private void ApplyTheme()
    {
        RootGrid.RequestedTheme = _settings.LightTheme ? ElementTheme.Light : ElementTheme.Dark;
        ThemeMenuItem.Text = _settings.LightTheme ? "Тёмная тема" : "Светлая тема";

        if (AppWindowTitleBar.IsCustomizationSupported())
        {
            var titleBar = AppWindow.TitleBar;
            titleBar.ButtonForegroundColor = _settings.LightTheme ? Colors.Black : Colors.White;
            titleBar.ButtonHoverBackgroundColor = _settings.LightTheme
                ? Color.FromArgb(20, 0, 0, 0)
                : Color.FromArgb(30, 255, 255, 255);
        }
    }

    // ─────────────────────────── запуск и подключение ───────────────────────────

    private async Task StartupAsync()
    {
        var host = _settings.Host;
        if (host == null) return;

        var token = _settings.TokenFor(host);
        if (token == null)
        {
            ShowConnectView();
            return;
        }

        await ConnectAsync(host, _settings.Port, token, remember: false);
    }

    private void ShowConnectView()
    {
        ConnectView.Visibility = Visibility.Visible;
        PlayerView.Visibility = Visibility.Collapsed;
        ForgetButton.Visibility = _settings.Host != null ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ShowPlayerView()
    {
        ConnectView.Visibility = Visibility.Collapsed;
        PlayerView.Visibility = Visibility.Visible;
    }

    private async Task ConnectAsync(string host, int port, string? token, bool remember)
    {
        ConnectButton.IsEnabled = false;
        ConnectErrorBar.IsOpen = false;

        try
        {
            await ReleaseClientAsync();

            var client = new SpeakerClient(host, port, token, ClientName, _settings.ClientId);
            await client.ConnectAsync();
            _client = client;
            client.Closed += OnClientClosed;

            if (remember) _settings.RememberSpeaker(host, port, _settings.DeviceName, _settings.DeviceModel, _settings.DeviceFirmware);

            ShowPlayerView();
            UpdateHeader(host);

            await RefreshAsync();
            await LoadMetadataAsync();

            _refreshTimer.Start();
            _keepAliveTimer.Start();
            _tickTimer.Start();
        }
        catch (AuthException)
        {
            _settings.SetToken(host, null);
            ShowConnectView();
            ShowConnectError("Колонка не приняла токен. Нажмите «+» на корпусе и нажмите «Сопряжение».");
        }
        catch (Exception ex)
        {
            ShowConnectView();
            ShowConnectError($"Не удалось подключиться: {ex.Message}");
        }
        finally
        {
            ConnectButton.IsEnabled = true;
        }
    }

    private void UpdateHeader(string host)
    {
        SpeakerName.Text = _settings.DeviceName ?? "SberBoom";
        HostText.Text = host;
        DeviceLine.Text = string.Join(" · ", new[]
        {
            _settings.DeviceModel,
            _settings.DeviceFirmware == null ? null : "fw " + _settings.DeviceFirmware,
        }.Where(s => !string.IsNullOrWhiteSpace(s)));
    }

    private void ShowConnectError(string message)
    {
        ConnectErrorBar.Message = message;
        ConnectErrorBar.IsOpen = true;
    }

    private void OnClientClosed(string reason)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            StatusText.Text = "Нет связи";
            StatusDot.Fill = new SolidColorBrush(Color.FromArgb(255, 196, 43, 28));
            ShowNotice("Соединение с колонкой закрыто: " + reason);
        });
    }

    // ─────────────────────────── состояние ───────────────────────────

    private async Task RefreshAsync()
    {
        if (_client == null) return;
        try
        {
            var state = await _client.GetStateAsync();
            if (state == null) return;
            _state = state;
            ApplyState(state);
        }
        catch (AuthException)
        {
            var host = _settings.Host;
            if (host != null) _settings.SetToken(host, null);
            _refreshTimer.Stop();
            _keepAliveTimer.Stop();
            await ReleaseClientAsync();
            ShowConnectView();
            ShowConnectError("Токен отозван. Нажмите «+» на колонке и повторите сопряжение.");
        }
        catch (Exception ex)
        {
            Program.Log("refresh: " + ex.Message);
        }
    }

    private async Task LoadMetadataAsync()
    {
        if (_client == null) return;
        try
        {
            var reply = await _client.RequestAsync(Requests.GetMetadata);
            var text = SpeakerClient.ExtractJson(reply.Body, Proto.OpGetMetaData);
            if (text == null) return;

            using var doc = System.Text.Json.JsonDocument.Parse(text);
            var root = doc.RootElement;

            string? Read(params string[] names)
            {
                foreach (var name in names)
                {
                    if (root.TryGetProperty(name, out var v) && v.ValueKind == System.Text.Json.JsonValueKind.String)
                        return v.GetString();
                }
                return null;
            }

            _settings.DeviceModel ??= Read("model");
            _settings.DeviceFirmware ??= Read("fw_version", "firmware", "firmwareVersion");
            var name = Read("name", "device_name");
            if (!string.IsNullOrWhiteSpace(name)) _settings.DeviceName = name;
            _settings.Save();
            UpdateHeader(_settings.Host ?? HostBox.Text);
        }
        catch (Exception ex)
        {
            Program.Log("metadata: " + ex.Message);
        }
    }

    private async Task KeepAliveAsync()
    {
        if (_client == null) return;
        try
        {
            await _client.FireAsync(Requests.KeepAlive);
        }
        catch (Exception ex)
        {
            Program.Log("keepalive: " + ex.Message);
        }
    }

    private void ApplyState(SpeakerState state)
    {
        var track = state.Track;

        _updatingUi = true;
        try
        {
            StatusText.Text = "На связи";
            StatusDot.Fill = (Brush)Application.Current.Resources["AccentFillColorDefaultBrush"];

            TrackTitle.Text = track?.Title ?? "Ничего не играет";
            TrackArtist.Text = !string.IsNullOrEmpty(track?.ArtistLine) ? track!.ArtistLine : track?.StationName ?? "";
            TrackAlbum.Text = track?.Album ?? "";

            BufferRing.Visibility = track?.Buffering == true ? Visibility.Visible : Visibility.Collapsed;
            BufferRing.IsActive = track?.Buffering == true;

            _shuffle = track?.Shuffle ?? false;
            _repeat = track?.Repeat ?? "none";
            _liked = track?.Liked ?? false;
            if (track?.Speed is { } s) _speed = s;

            ShuffleIcon.Foreground = _shuffle ? AccentBrush() : MutedBrush();
            LikeIcon.Foreground = _liked ? AccentBrush() : MutedBrush();
            RepeatIcon.Foreground = _repeat == "none" ? MutedBrush() : AccentBrush();
            RepeatIcon.Glyph = _repeat == "track" ? "\uE8ED" : "\uE8EE";
            SpeedText.Text = FormatSpeed(_speed);
            PlayIcon.Glyph = track?.Playing == true ? "\uE769" : "\uE768";

            var label = SourceLabel(track);
            SourceBadge.Visibility = label == null ? Visibility.Collapsed : Visibility.Visible;
            if (label != null) SourceText.Text = label;

            UpdateCover(track);
            UpdateProgress(track);
            UpdateSleepLabel();

            var duration = track?.DurationSec ?? 0;
            if (!_updatingUi || !_seekPending)
            {
                ProgressSlider.IsEnabled = duration > 0;
            }

            VolumeSlider.Value = state.Volume;
            VolumeText.Text = state.Volume + "%";
            MuteIcon.Glyph = state.Muted || state.Volume == 0 ? "\uE74F" : "\uE767";
            MuteIcon.Foreground = state.Muted ? ErrorBrush() : MutedBrush();
        }
        finally
        {
            _updatingUi = false;
        }

        _ = EnsureLyricsAsync(track);
        if (track?.Playing == true && !string.IsNullOrEmpty(track.TrackId))
        {
            _settings.PushHistory(track.TrackId, track.Title, track.ArtistLine, track.CoverUrl);
        }
    }

    private void UpdateCover(Track? track)
    {
        var url = track?.CoverUrl;
        if (url == _coverUrl) return;
        _coverUrl = url;

        if (string.IsNullOrEmpty(url))
        {
            CoverImage.Source = null;
            CoverPlaceholder.Visibility = Visibility.Visible;
            return;
        }

        CoverPlaceholder.Visibility = Visibility.Collapsed;
        try
        {
            CoverImage.Source = new BitmapImage(new Uri(url));
        }
        catch (Exception ex)
        {
            Program.Log("cover: " + ex.Message);
        }
    }

    private void UpdateProgress(Track? track, bool force = false)
    {
        var duration = track?.DurationSec ?? 0;
        if (duration <= 0)
        {
            ProgressSlider.IsEnabled = false;
            if (!_seekPending) ProgressSlider.Value = 0;
            PositionText.Text = "";
            DurationText.Text = "прямой эфир";
            return;
        }

        ProgressSlider.Maximum = duration;
        if (_seekPending && !force) return;

        var position = Math.Clamp(track!.LivePositionSec(), 0, duration);
        ProgressSlider.Value = position;
        PositionText.Text = FormatTime(position);
        DurationText.Text = FormatTime(duration);
    }

    private void OnTick()
    {
        if (_state?.Track == null) return;
        UpdateProgress(_state.Track);
        UpdateLyricLine();
        CheckSleepTimer();
    }

    private async Task EnsureLyricsAsync(Track? track)
    {
        if (track == null) return;
        var key = $"{track.TrackId}|{track.ArtistLine}|{track.Title}";
        if (key == _lyricsFor) return;

        _lyricsFor = key;
        _lyrics = new List<LrcLine>();
        TrackLyricLine.Visibility = Visibility.Collapsed;

        if (string.IsNullOrWhiteSpace(track.Title)) return;

        var lines = await LyricsApi.GetAsync(track.ArtistLine, track.Title, track.Album, track.DurationSec);
        if (key != _lyricsFor) return;
        _lyrics = lines;
        UpdateLyricLine();
    }

    private void UpdateLyricLine()
    {
        if (_lyrics.Count == 0 || _state?.Track == null)
        {
            TrackLyricLine.Visibility = Visibility.Collapsed;
            return;
        }

        var position = (float)_state.Track.LivePositionSec();
        var index = LyricsApi.ActiveIndex(_lyrics, position);
        if (index < 0)
        {
            TrackLyricLine.Visibility = Visibility.Collapsed;
            return;
        }

        TrackLyricLine.Text = _lyrics[index].Text;
        TrackLyricLine.Visibility = Visibility.Visible;
    }

    // ─────────────────────────── команды ───────────────────────────

    private async Task CommandAsync(byte[] request)
    {
        if (_client == null) return;
        try
        {
            await _client.FireAsync(request);
            await Task.Delay(200);
            await RefreshAsync();
        }
        catch (Exception ex)
        {
            Program.Log("command: " + ex.Message);
            ShowNotice("Колонка не приняла команду: " + ex.Message);
        }
    }

    private void Play_Click(object sender, RoutedEventArgs e) =>
        _ = CommandAsync(Requests.Media(_state?.Track?.Playing == true ? Proto.MediaPause : Proto.MediaPlay));

    private void Next_Click(object sender, RoutedEventArgs e) => _ = CommandAsync(Requests.Media(Proto.MediaNext));

    private void Prev_Click(object sender, RoutedEventArgs e) => _ = CommandAsync(Requests.Media(Proto.MediaPrev));

    private void Shuffle_Click(object sender, RoutedEventArgs e) =>
        _ = CommandAsync(Requests.Media(_shuffle ? Proto.MediaShuffleOff : Proto.MediaShuffleOn));

    private void Repeat_Click(object sender, RoutedEventArgs e)
    {
        var next = _repeat switch
        {
            "none" => Proto.MediaRepeatPlaylist,
            "playlist" => Proto.MediaRepeatTrack,
            _ => Proto.MediaRepeatNone,
        };
        _ = CommandAsync(Requests.Media(next));
    }

    private void Like_Click(object sender, RoutedEventArgs e) =>
        _ = CommandAsync(Requests.Media(_liked ? Proto.MediaRemoveLike : Proto.MediaLike));

    private void Dislike_Click(object sender, RoutedEventArgs e) =>
        _ = CommandAsync(Requests.Media(Proto.MediaDislike));

    private void Mute_Click(object sender, RoutedEventArgs e) =>
        _ = CommandAsync(Requests.Media(_state?.Muted == true ? Proto.MediaUnmute : Proto.MediaMute));

    private void Speed_Click(object sender, RoutedEventArgs e)
    {
        var next = _speed switch
        {
            < 0.75f => 0.75f,
            < 1f => 1f,
            < 1.25f => 1.25f,
            < 1.5f => 1.5f,
            < 1.75f => 1.75f,
            _ => 0.5f,
        };
        _ = CommandAsync(Requests.Speed(next));
    }

    private void ProgressSlider_ValueChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (_updatingUi) return;
        var value = (int)Math.Round(e.NewValue);
        PositionText.Text = FormatTime(value);
        _seekTarget = value;
        _seekPending = true;
        _seekTimer.Stop();
        _seekTimer.Start();
    }

    private void FlushSeek()
    {
        _seekTimer.Stop();
        if (!_seekPending) return;
        _seekPending = false;
        _ = CommandAsync(Requests.Seek(_seekTarget));
    }

    private async void VolumeSlider_ValueChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        VolumeText.Text = (int)Math.Round(e.NewValue) + "%";
        if (_updatingUi || _state == null) return;

        var value = (int)Math.Round(e.NewValue);
        if (Math.Abs(value - _state.Volume) < 1) return;

        await CommandAsync(Requests.Volume(value));
    }

    /// <summary>Воспроизведение через ServerAction DEEPLINK (как в Android-версии).</summary>
    private async Task PlayDeeplinkAsync(string? deeplink)
    {
        if (deeplink == null)
        {
            ShowNotice("Не удалось собрать ссылку на этот элемент");
            return;
        }
        if (_client == null)
        {
            ShowNotice("Нет соединения с колонкой");
            return;
        }

        try
        {
            var payload = System.Text.Json.JsonSerializer.Serialize(new { deeplink });
            await _client.FireServerActionAsync(Requests.ServerActionJson("DEEPLINK", payload));
            ShowNotice("Включаю на колонке");
            await Task.Delay(900);
            await RefreshAsync();
        }
        catch (Exception ex)
        {
            ShowNotice("Колонка не приняла команду: " + ex.Message);
        }
    }

    // ─────────────────────────── сопряжение ───────────────────────────

    private async void PairButton_Click(object sender, RoutedEventArgs e) => await PairAsync();

    private async void PairAgain_Click(object sender, RoutedEventArgs e) => await PairAsync();

    private async Task PairAsync()
    {
        var host = HostBox.Text.Trim();
        if (string.IsNullOrEmpty(host)) host = _settings.Host ?? "";
        if (string.IsNullOrEmpty(host))
        {
            ShowConnectError("Укажите адрес колонки");
            return;
        }

        var dialog = BuildPairingDialog();
        var dialogTask = dialog.ShowAsync();

        try
        {
            if (_client is not { IsOpen: true })
            {
                await ReleaseClientAsync();
                _client = new SpeakerClient(host, _settings.Port, null, ClientName, _settings.ClientId);
                await _client.ConnectAsync();
                _client.Closed += OnClientClosed;
            }

            var token = await _client.PairAsync();
            _settings.SetToken(host, token);
            _settings.Host = host;
            _settings.Save();

            dialog.Hide();

            ShowPlayerView();
            UpdateHeader(host);
            await RefreshAsync();
            await LoadMetadataAsync();

            _refreshTimer.Start();
            _keepAliveTimer.Start();
            _tickTimer.Start();
        }
        catch (Exception ex)
        {
            dialog.Hide();
            ShowConnectView();
            ShowConnectError(ex.Message);
        }
        finally
        {
            try { await dialogTask; } catch { /* диалог закрыт */ }
        }
    }

    private ContentDialog BuildPairingDialog()
    {
        var content = new StackPanel { Spacing = 12, Width = 300 };
        content.Children.Add(new FontIcon
        {
            Glyph = "\uE7F5",
            FontSize = 32,
            Foreground = AccentBrush(),
        });
        content.Children.Add(new TextBlock
        {
            Text = "Нажмите «+» на колонке",
            Style = (Style)Application.Current.Resources["BodyStrongTextBlockStyle"],
        });
        content.Children.Add(new TextBlock
        {
            Text = "Один раз подтвердите доступ кнопкой громкости «+» на корпусе SberBoom. Токен сохранится, дальше подключение автоматическое.",
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["BodyTextBlockStyle"],
            Foreground = MutedBrush(),
        });
        content.Children.Add(new ProgressBar { IsIndeterminate = true });

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = "Сопряжение",
            Content = content,
            CloseButtonText = "Отменить",
        };
        dialog.CloseButtonClick += (_, _) => _ = ReleaseClientAsync();
        return dialog;
    }

    // ─────────────────────────── поиск и сервисные пункты ───────────────────────────

    private async void ScanButton_Click(object sender, RoutedEventArgs e)
    {
        ScanButton.IsEnabled = false;
        ScanRing.Visibility = Visibility.Visible;
        ScanRing.IsActive = true;
        ScanStatus.Text = "Сканирую mDNS и порт 20000 в подсети…";
        SpeakersList.Items.Clear();

        try
        {
            var found = await Discovery.ScanAsync(4000);
            foreach (var speaker in found) SpeakersList.Items.Add(speaker.Detail);
            ScanStatus.Text = found.Count == 0
                ? "Ничего не нашлось — введите IP вручную"
                : $"Найдено колонок: {found.Count}";
        }
        catch (Exception ex)
        {
            ScanStatus.Text = "Поиск не удался: " + ex.Message;
        }
        finally
        {
            ScanRing.IsActive = false;
            ScanRing.Visibility = Visibility.Collapsed;
            ScanButton.IsEnabled = true;
        }
    }

    private void SpeakersList_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not string detail) return;
        var host = detail.Split(' ')[0].Split(':')[0];
        HostBox.Text = host;
        _ = ConnectOrPairAsync(host);
    }

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        var host = HostBox.Text.Trim();
        if (string.IsNullOrEmpty(host))
        {
            ShowConnectError("Укажите адрес колонки");
            return;
        }
        await ConnectOrPairAsync(host);
    }

    private async Task ConnectOrPairAsync(string host)
    {
        var token = _settings.TokenFor(host);
        if (token == null)
        {
            _settings.Host = host;
            _settings.Save();
            await PairAsync();
            return;
        }
        await ConnectAsync(host, _settings.Port, token, remember: true);
    }

    private void ForgetButton_Click(object sender, RoutedEventArgs e)
    {
        if (_settings.Host != null) _settings.ForgetSpeaker(_settings.Host);
        _ = ReleaseClientAsync();
        ShowConnectView();
        ShowNotice("Колонка забыта — токен удалён");
    }

    private void Disconnect_Click(object sender, RoutedEventArgs e)
    {
        _ = ReleaseClientAsync();
        ShowConnectView();
    }

    private void ChangeSpeaker_Click(object sender, RoutedEventArgs e)
    {
        _refreshTimer.Stop();
        _keepAliveTimer.Stop();
        _tickTimer.Stop();
        ShowConnectView();
    }

    private async Task ReleaseClientAsync()
    {
        _refreshTimer.Stop();
        _keepAliveTimer.Stop();
        _tickTimer.Stop();
        _seekTimer.Stop();
        if (_client != null)
        {
            _client.Closed -= OnClientClosed;
            await _client.DisposeAsync();
            _client = null;
        }
        _state = null;
        _coverUrl = null;
        _lyricsFor = null;
        _lyrics = new List<LrcLine>();
        TrackLyricLine.Visibility = Visibility.Collapsed;
        CoverImage.Source = null;
        CoverPlaceholder.Visibility = Visibility.Visible;
    }

    private void Refresh_Click(object sender, RoutedEventArgs e) => _ = RefreshAsync();

    private void Theme_Click(object sender, RoutedEventArgs e)
    {
        _settings.LightTheme = !_settings.LightTheme;
        _settings.Save();
        ApplyTheme();
    }

    private void ShowNotice(string message)
    {
        Notice.Title = "SberBoom";
        Notice.Message = message;
        Notice.IsOpen = true;
    }

    // ─────────────────────────── таймер сна ───────────────────────────

    private void OpenSleepTimer_Click(object sender, RoutedEventArgs e) => _ = ShowSleepTimerAsync();

    private async Task ShowSleepTimerAsync()
    {
        var panel = new StackPanel { Spacing = 8 };
        var options = new (string Label, int Minutes)[] { ("15 минут", 15), ("30 минут", 30), ("60 минут", 60), ("90 минут", 90) };

        foreach (var (label, minutes) in options)
        {
            var button = new Button { Content = label, HorizontalAlignment = HorizontalAlignment.Stretch };
            button.Click += async (_, _) =>
            {
                _settings.SleepUntilMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + minutes * 60_000L;
                _settings.Save();
                _sleepFired = false;
                UpdateSleepLabel();
                ShowNotice($"Таймер сна: {minutes} мин");
                await Task.CompletedTask;
            };
            panel.Children.Add(button);
        }

        var offButton = new Button { Content = "Выключить таймер", HorizontalAlignment = HorizontalAlignment.Stretch };
        offButton.Click += (_, _) =>
        {
            _settings.SleepUntilMs = null;
            _settings.Save();
            UpdateSleepLabel();
        };
        panel.Children.Add(offButton);

        await ShowDialogAsync("Таймер сна", panel);
    }

    private void UpdateSleepLabel()
    {
        var until = _settings.SleepUntilMs;
        if (until == null)
        {
            SleepTimerText.Visibility = Visibility.Collapsed;
            return;
        }

        var left = until.Value - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (left <= 0)
        {
            SleepTimerText.Visibility = Visibility.Collapsed;
            return;
        }

        var minutes = (int)Math.Ceiling(left / 60_000.0);
        SleepTimerText.Text = $"Таймер сна: осталось {minutes} мин";
        SleepTimerText.Visibility = Visibility.Visible;
    }

    private void CheckSleepTimer()
    {
        var until = _settings.SleepUntilMs;
        if (until == null) return;

        if (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() < until.Value)
        {
            UpdateSleepLabel();
            return;
        }

        if (_sleepFired) return;
        _sleepFired = true;
        _settings.SleepUntilMs = null;
        _settings.Save();
        UpdateSleepLabel();
        _ = CommandAsync(Requests.Media(Proto.MediaPause));
        ShowNotice("Таймер сна: воспроизведение остановлено");
    }

    // ─────────────────────────── helpers ───────────────────────────

    private async Task ShowDialogAsync(string title, object content, string closeText = "Закрыть")
    {
        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = title,
            Content = content,
            CloseButtonText = closeText,
            DefaultButton = ContentDialogButton.Close,
        };
        await dialog.ShowAsync();
    }

    private static Brush Resource(string key) => (Brush)Application.Current.Resources[key];

    private static Brush AccentBrush() => Resource("AccentTextFillColorPrimaryBrush");

    private static Brush MutedBrush() => Resource("TextFillColorSecondaryBrush");

    private static Brush TextBrush() => Resource("TextFillColorPrimaryBrush");

    private static Brush ErrorBrush() => Resource("SystemFillColorCriticalBrush");

    private static string? SourceLabel(Track? track)
    {
        if (track == null) return null;
        if (track.Source == Source.Bluetooth) return "Bluetooth · " + track.ArtistLine;
        if (track.Source == Source.Radio) return track.StationName ?? "Радио";
        if (track.PlaylistTitle != null)
            return track.PlaylistTitle + (track.Provider == "zvuk" ? " · Sber Звук" : "");
        if (track.Provider == "zvuk") return "Sber Звук";
        return null;
    }

    private static string FormatSpeed(float speed) =>
        Math.Abs(speed - MathF.Round(speed)) < 0.01f
            ? (int)MathF.Round(speed) + "×"
            : speed.ToString("0.##").Replace('.', ',') + "×";

    private static string FormatTime(int seconds)
    {
        var s = Math.Max(0, seconds);
        return $"{s / 60}:{s % 60:00}";
    }
}
