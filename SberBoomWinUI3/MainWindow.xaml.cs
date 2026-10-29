using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
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
/// Окно приложения: экран подключения, плеер, панель состояния и сопряжение.
/// Логика повторяет Android/Compose-версию: тот же протокол и команды,
/// тот же порядок сопряжения (op=4 → «+» на колонке → op=6 с токеном).
/// </summary>
public sealed partial class MainWindow : Window
{
    private const string ClientName = Settings.DefaultClientName;

    private readonly Settings _settings = Settings.Load();
    private readonly DispatcherTimer _refreshTimer = new() { Interval = TimeSpan.FromSeconds(2) };
    private readonly DispatcherTimer _keepAliveTimer = new() { Interval = TimeSpan.FromSeconds(20) };
    private readonly DispatcherTimer _tickTimer = new() { Interval = TimeSpan.FromSeconds(1) };

    private SpeakerClient? _client;
    private SpeakerState? _state;
    private string? _coverUrl;
    private bool _volumeBusy;
    private bool _shuffle;
    private string _repeat = "none";
    private bool _liked;
    private float _speed = 1f;

    public MainWindow()
    {
        InitializeComponent();
        Title = "SberBoom Remote";
        ResizeWindow(460, 900);

        RootGrid.RequestedTheme = _settings.LightTheme ? ElementTheme.Light : ElementTheme.Dark;
        ThemeMenuItem.Text = _settings.LightTheme ? "Тёмная тема" : "Светлая тема";

        Progress.Seek += OnProgressSeek;
        Progress.SeekFinished += OnProgressSeekFinished;

        _refreshTimer.Tick += (_, _) => _ = RefreshAsync();
        _keepAliveTimer.Tick += (_, _) => _ = KeepAliveAsync();
        _tickTimer.Tick += (_, _) => UpdateTimes();

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
        ConnectError.Visibility = Visibility.Collapsed;

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
            // PIN-токен живёт до следующего сопряжения: просим нажать «+» снова
            _settings.SetToken(host, null);
            ShowConnectView();
            ShowConnectError("Колонка не приняла токен — нажмите «+» на корпусе, чтобы сопрячься заново.");
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
        ConnectError.Text = message;
        ConnectError.Visibility = Visibility.Visible;
    }

    private void OnClientClosed(string reason)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            StatusText.Text = "Нет связи";
            StatusDot.Fill = new SolidColorBrush(Color.FromArgb(255, 255, 138, 138));
            Progress.Animated = false;
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

        StatusText.Text = "На связи";
        StatusDot.Fill = AccentBrush();

        TrackTitle.Text = track?.Title ?? "Ничего не играет";
        TrackArtist.Text = !string.IsNullOrEmpty(track?.ArtistLine) ? track!.ArtistLine : track?.StationName ?? "";
        TrackAlbum.Text = track?.Album ?? "";

        Buffering(track?.Buffering == true);

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
        Progress.Animated = track?.Playing == true;

        if (!_volumeBusy)
        {
            VolumeSlider.Value = state.Volume;
            VolumeText.Text = state.Volume + "%";
        }
        MuteIcon.Glyph = state.Muted || state.Volume == 0 ? "\uE74F" : "\uE767";
        MuteIcon.Foreground = state.Muted ? ErrorBrush() : MutedBrush();

        var label = SourceLabel(track);
        SourceBadge.Visibility = label == null ? Visibility.Collapsed : Visibility.Visible;
        if (label != null) SourceText.Text = label;

        UpdateCover(track);
        UpdateProgressBar(track);
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

    private void UpdateProgressBar(Track? track)
    {
        var duration = track?.DurationSec ?? 0;
        if (duration <= 0)
        {
            Progress.Progress = 0;
            Progress.Seekable = false;
            PositionText.Text = "";
            DurationText.Text = "прямой эфир";
            return;
        }

        Progress.Seekable = true;
        var position = track!.LivePositionSec();
        Progress.Progress = Math.Clamp((double)position / duration, 0, 1);
        PositionText.Text = FormatTime(position);
        DurationText.Text = FormatTime(duration);
    }

    private void UpdateTimes()
    {
        if (_state?.Track == null) return;
        UpdateProgressBar(_state.Track);
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

    private void FindRemote_Click(object sender, RoutedEventArgs e)
    {
        _ = CommandAsync(Requests.FindRemote);
        ShowNotice("Колонка звенит — ищите пульт рядом");
    }

    private void OnProgressSeek(object? sender, double fraction)
    {
        var duration = _state?.Track?.DurationSec ?? 0;
        if (duration <= 0) return;
        PositionText.Text = FormatTime((int)Math.Round(fraction * duration));
    }

    private void OnProgressSeekFinished(object? sender, double fraction)
    {
        var duration = _state?.Track?.DurationSec ?? 0;
        if (duration <= 0) return;
        _ = CommandAsync(Requests.Seek((int)Math.Round(fraction * duration)));
    }

    private async void VolumeSlider_ValueChanged(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        VolumeText.Text = (int)Math.Round(e.NewValue) + "%";
        if (_state == null || _volumeBusy) return;

        var value = (int)Math.Round(e.NewValue);
        if (Math.Abs(value - _state.Volume) < 1) return;

        _volumeBusy = true;
        try
        {
            await CommandAsync(Requests.Volume(value));
        }
        finally
        {
            _volumeBusy = false;
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

        PairingOverlay.Visibility = Visibility.Visible;

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

            PairingOverlay.Visibility = Visibility.Collapsed;

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
            PairingOverlay.Visibility = Visibility.Collapsed;
            ShowConnectView();
            ShowConnectError(ex.Message);
        }
    }

    private void CancelPairing_Click(object sender, RoutedEventArgs e)
    {
        PairingOverlay.Visibility = Visibility.Collapsed;
        _ = ReleaseClientAsync();
    }

    // ─────────────────────────── поиск и UI ───────────────────────────

    private async void ScanButton_Click(object sender, RoutedEventArgs e)
    {
        ScanButton.IsEnabled = false;
        ScanRing.Visibility = Visibility.Visible;
        ScanRing.IsActive = true;
        ScanStatus.Text = "Сканирую mDNS и порт 20000 в подсети…";
        SpeakersList.Items.Clear();
        ForgetButton.Visibility = Visibility.Collapsed;

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
            // без токена единственный путь — сопряжение кнопкой «+» на колонке
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
        if (_client != null)
        {
            _client.Closed -= OnClientClosed;
            await _client.DisposeAsync();
            _client = null;
        }
        _state = null;
        _coverUrl = null;
        CoverImage.Source = null;
        CoverPlaceholder.Visibility = Visibility.Visible;
    }

    private void MenuButton_Click(object sender, RoutedEventArgs e)
    {
        // меню открывается самим Flyout у кнопки
    }

    private void Refresh_Click(object sender, RoutedEventArgs e) => _ = RefreshAsync();

    private void DevicePanel_Click(object sender, RoutedEventArgs e)
    {
        DeviceDetails.Children.Clear();

        void Row(string label, string? value)
        {
            if (string.IsNullOrWhiteSpace(value)) return;
            var line = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            line.Children.Add(new TextBlock
            {
                Text = label,
                Width = 132,
                FontSize = 13,
                Foreground = MutedBrush(),
            });
            line.Children.Add(new TextBlock
            {
                Text = value,
                FontSize = 13,
                MaxWidth = 240,
                TextWrapping = TextWrapping.Wrap,
                Foreground = TextBrush(),
            });
            DeviceDetails.Children.Add(line);
        }

        var device = _state?.Device;
        Row("Колонка", _settings.DeviceName);
        Row("Модель", _settings.DeviceModel);
        Row("Прошивка", _settings.DeviceFirmware);
        Row("Адрес", _settings.Host);
        Row("Сеть", device?.NetworkType);
        Row("Громкость", _state == null ? null : _state.Volume + "%" + (_state.Muted ? " · без звука" : ""));
        Row("Подсветка", device?.LedOn switch { true => "включена", false => "выключена", _ => null });
        Row("Яркость LED", device?.LedBrightness?.ToString());
        Row("Будильники", device?.AlarmsCount?.ToString());
        Row("Трек", _state?.Track?.Title);
        Row("Источник", _state?.Track?.Provider);
        Row("Скорость", _state?.Track?.Speed is { } sp ? FormatSpeed(sp) : null);

        DeviceOverlay.Visibility = Visibility.Visible;
    }

    private void CloseDevicePanel_Click(object sender, RoutedEventArgs e) =>
        DeviceOverlay.Visibility = Visibility.Collapsed;

    private void Theme_Click(object sender, RoutedEventArgs e)
    {
        _settings.LightTheme = !_settings.LightTheme;
        _settings.Save();
        RootGrid.RequestedTheme = _settings.LightTheme ? ElementTheme.Light : ElementTheme.Dark;
        ThemeMenuItem.Text = _settings.LightTheme ? "Тёмная тема" : "Светлая тема";
    }

    private void ShowNotice(string message)
    {
        Notice.Title = "SberBoom";
        Notice.Message = message;
        Notice.IsOpen = true;
    }

    private void Buffering(bool active)
    {
        BufferRing.Visibility = active ? Visibility.Visible : Visibility.Collapsed;
        BufferRing.IsActive = active;
    }

    private static Brush Resource(string key) => (Brush)Application.Current.Resources[key];

    private static Brush AccentBrush() => Resource("AppAccent");

    private static Brush MutedBrush() => Resource("AppTextMuted");

    private static Brush TextBrush() => Resource("AppText");

    private static Brush ErrorBrush() => Resource("AppError");

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
