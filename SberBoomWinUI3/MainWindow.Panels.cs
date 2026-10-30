using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using SberBoom.WinUI.Data;
using SberBoom.WinUI.Net;
using SberBoom.WinUI.Protocol;

namespace SberBoom.WinUI;

/// <summary>
/// Панели окна: поиск и каталог Звука, очередь, текст песни, Bluetooth,
/// история, диагностика связи, состояние колонки.
/// Всё, что было в Compose-версии, кроме Яндекс Музыки (от неё отказались).
/// </summary>
public sealed partial class MainWindow
{
    // ─────────────────────────── поиск в Звуке ───────────────────────────

    private async void OpenSearch_Click(object sender, RoutedEventArgs e) => await SearchDialogAsync();

    private async Task SearchDialogAsync()
    {
        var panel = new StackPanel { Spacing = 10 };
        var input = new TextBox { PlaceholderText = "Трек, исполнитель, альбом или ссылка zvuk.com", CornerRadius = new CornerRadius(4) };
        var status = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = MutedBrush(),
            Text = "Введите запрос и нажмите Enter",
        };
        var results = new ListView { Height = 320, SelectionMode = ListViewSelectionMode.Single };

        panel.Children.Add(input);
        panel.Children.Add(status);
        panel.Children.Add(results);

        var items = new List<ZvukItem>();

        async Task RunSearchAsync()
        {
            var query = input.Text.Trim();
            if (query.Length == 0) return;

            // Ссылка zvuk.com/<kind>/<id> — играем сразу, без поиска
            var linkMatch = System.Text.RegularExpressions.Regex.Match(query,
                @"zvuk\.com/(track|artist|release|playlist|abook)/(\d+)",
                System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            if (linkMatch.Success)
            {
                var kind = linkMatch.Groups[1].Value.ToLowerInvariant() == "abook"
                    ? "podcast"
                    : linkMatch.Groups[1].Value.ToLowerInvariant();
                await PlayDeeplinkAsync(ZvukApi.Deeplink(kind, linkMatch.Groups[2].Value));
                return;
            }

            status.Text = "Ищу…";
            results.Items.Clear();
            items = await _zvuk.SearchAsync(query);
            foreach (var item in items) results.Items.Add(item.Line);
            status.Text = items.Count == 0 ? "Ничего не нашлось" : $"Найдено: {items.Count}";
        }

        input.KeyDown += async (_, args) =>
        {
            if (args.Key == Windows.System.VirtualKey.Enter) await RunSearchAsync();
        };

        var searchButton = new Button { Content = "Найти", HorizontalAlignment = HorizontalAlignment.Stretch };
        searchButton.Click += async (_, _) => await RunSearchAsync();
        panel.Children.Insert(2, searchButton);

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = "Поиск в Звуке",
            Content = panel,
            CloseButtonText = "Закрыть",
        };

        results.ItemClick += async (_, args) =>
        {
            if (args.ClickedItem is not string line) return;
            var index = results.Items.IndexOf(line);
            if (index < 0 || index >= items.Count) return;
            var item = items[index];
            await PlayDeeplinkAsync(ZvukApi.Deeplink(item.Type, item.Id));
            dialog.Hide();
        };
        results.IsItemClickEnabled = true;

        await ShowDialogGuardedAsync(dialog);
    }

    // ─────────────────────────── очередь ───────────────────────────

    private async void OpenQueue_Click(object sender, RoutedEventArgs e) => await QueueDialogAsync();

    private async Task QueueDialogAsync()
    {
        if (_client == null)
        {
            ShowNotice("Нет соединения с колонкой");
            return;
        }

        var panel = new StackPanel { Spacing = 10 };
        var status = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = MutedBrush(),
            Text = "Читаю очередь…",
        };
        var list = new ListView
        {
            Height = 340,
            SelectionMode = ListViewSelectionMode.Single,
            IsItemClickEnabled = true,
        };
        panel.Children.Add(status);
        panel.Children.Add(list);

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = "Что дальше",
            Content = panel,
            CloseButtonText = "Закрыть",
        };

        List<ZvukItem> tracks = new();
        list.ItemClick += async (_, args) =>
        {
            if (args.ClickedItem is not string line) return;
            var index = list.Items.IndexOf(line);
            if (index < 0 || index >= tracks.Count) return;
            await PlayDeeplinkAsync(ZvukApi.Deeplink("track", tracks[index].Id));
            dialog.Hide();
        };

        _ = Task.Run(async () =>
        {
            try
            {
                var raw = await _client!.RequestRawAsync(Requests.GetQueue);
                var ids = Parsers.ParseQueueTrackIds(raw);

                DispatcherQueue.TryEnqueue(() =>
                {
                    if (ids.Count == 0)
                    {
                        status.Text = "Колонка не отдала очередь (пусто или идёт прямой эфир)";
                        return;
                    }

                    for (var i = 0; i < ids.Count; i++) list.Items.Add($"#{i + 1}  трек {ids[i]}");
                    status.Text = $"Треков в очереди: {ids.Count} — подтягиваю названия…";
                });

                var meta = await _zvuk.TracksAsync(ids);
                DispatcherQueue.TryEnqueue(() =>
                {
                    tracks = ids.Select(id => meta.TryGetValue(id, out var m) && !string.IsNullOrWhiteSpace(m.Title)
                            ? m
                            : new ZvukItem { Id = id, Type = "track", Title = "трек " + id })
                        .ToList();

                    list.Items.Clear();
                    foreach (var t in tracks) list.Items.Add($"{t.Title} — {t.Subtitle}".TrimEnd(' ', '—'));
                    status.Text = $"Треков в очереди: {tracks.Count}";
                });
            }
            catch (Exception ex)
            {
                DispatcherQueue.TryEnqueue(() => status.Text = "Не удалось прочитать очередь: " + ex.Message);
            }
        });

        await ShowDialogGuardedAsync(dialog);
    }

    // ─────────────────────────── текст песни ───────────────────────────

    private async void OpenLyrics_Click(object sender, RoutedEventArgs e) => await LyricsDialogAsync();

    private async Task LyricsDialogAsync()
    {
        var track = _state?.Track;
        if (track == null || string.IsNullOrWhiteSpace(track.Title))
        {
            ShowNotice("Трек не играет — текста нет");
            return;
        }

        if (_lyrics.Count == 0)
        {
            await EnsureLyricsAsync(track);
        }

        var panel = new StackPanel { Spacing = 8 };
        var hasSynced = _lyrics.Count > 0;
        var status = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = MutedBrush(),
            Text = hasSynced
                ? $"{track.Title} — {track.ArtistLine}"
                : _lyricsInstrumental
                    ? "Инструментальная композиция — текста нет"
                    : string.IsNullOrWhiteSpace(_lyricsPlain)
                        ? "Текст не найден"
                        : "Текст без синхронизации",
        };
        var list = new ListView { Height = 380, SelectionMode = ListViewSelectionMode.None };
        panel.Children.Add(status);
        panel.Children.Add(list);

        // Поправка: текст на колонке и тайминги из базы могут расходиться на доли секунды,
        // угадать это нельзя — даём подстроить вручную и запоминаем.
        var leadLabel = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = MutedBrush(),
            Text = LyricLeadLabel(),
            VerticalAlignment = VerticalAlignment.Center,
        };
        var earlier = new Button { Content = "Текст раньше" };
        var later = new Button { Content = "Текст позже" };
        earlier.Click += (_, _) => { NudgeLyricLead(-250); leadLabel.Text = LyricLeadLabel(); };
        later.Click += (_, _) => { NudgeLyricLead(250); leadLabel.Text = LyricLeadLabel(); };
        var leadRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
            Margin = new Thickness(0, 4, 0, 0),
        };
        leadRow.Children.Add(earlier);
        leadRow.Children.Add(later);
        leadRow.Children.Add(leadLabel);
        panel.Children.Add(leadRow);

        foreach (var line in _lyrics)
        {
            list.Items.Add(new TextBlock
            {
                Text = line.Text,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 2, 0, 2),
            });
        }

        if (!hasSynced && !string.IsNullOrWhiteSpace(_lyricsPlain))
        {
            foreach (var line in _lyricsPlain!.Split(@"
"))
            {
                list.Items.Add(new TextBlock
                {
                    Text = line.TrimEnd(),
                    TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(0, 2, 0, 2),
                });
            }
        }

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = "Текст песни",
            Content = panel,
            CloseButtonText = "Закрыть",
        };

        // подсветка активной строки и прокрутка к ней
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(600) };
        timer.Tick += (_, _) =>
        {
            if (_state?.Track == null || _lyrics.Count == 0) return;
            var index = LyricsApi.ActiveIndex(_lyrics, (float)_state.Track.LivePositionSec(), LyricLeadSec);
            if (index < 0 || index >= list.Items.Count) return;

            for (var i = 0; i < list.Items.Count; i++)
            {
                if (list.Items[i] is TextBlock block)
                {
                    block.FontWeight = i == index ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal;
                    block.Foreground = i == index ? AccentBrush() : TextBrush();
                }
            }
            list.ScrollIntoView(list.Items[index]);
        };

        dialog.Opened += (_, _) => timer.Start();
        dialog.Closed += (_, _) => timer.Stop();

        await ShowDialogGuardedAsync(dialog);
    }

    // ─────────────────────────── Bluetooth ───────────────────────────

    private async void OpenBluetooth_Click(object sender, RoutedEventArgs e) => await BluetoothDialogAsync();

    private async Task BluetoothDialogAsync()
    {
        if (_client == null)
        {
            ShowNotice("Нет соединения с колонкой");
            return;
        }

        var panel = new StackPanel { Spacing = 10 };
        var status = new TextBlock
        {
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            Foreground = MutedBrush(),
            Text = "Читаю список спаренных устройств…",
        };
        var list = new StackPanel { Spacing = 6 };
        panel.Children.Add(status);
        panel.Children.Add(list);

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = "Bluetooth",
            Content = new ScrollViewer { Content = panel, MaxHeight = 420 },
            CloseButtonText = "Закрыть",
        };

        string signature = "";
        var lastDevices = new List<BtDevice>();
        var busy = false;

        static string SignatureOf(List<BtDevice> devices) =>
            string.Join("|", devices.Select(d => d.Mac + ":" + d.Connected));

        void Render(List<BtDevice> devices)
        {
            signature = SignatureOf(devices);
            list.Children.Clear();
            if (devices.Count == 0)
            {
                status.Text = "Спаренных устройств нет. Включите режим сопряжения и подключите телефон.";
            }
            else if (signature == SignatureOf(devices) && busy)
            {
                // список не изменился, а команда ещё выполняется — не затираем её статус
            }
            else
            {
                status.Text = $"Спарено устройств: {devices.Count}";
            }

            foreach (var device in devices)
            {
                var row = new Grid { ColumnSpacing = 8 };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

                var name = new TextBlock
                {
                    Text = string.IsNullOrWhiteSpace(device.Name) ? device.Mac : device.Name,
                    Style = (Style)Application.Current.Resources["BodyTextBlockStyle"],
                    VerticalAlignment = VerticalAlignment.Center,
                    TextTrimming = TextTrimming.CharacterEllipsis,
                };
                var state = new TextBlock
                {
                    Text = device.Connected ? "подключено" : device.Mac,
                    Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                    Foreground = device.Connected ? AccentBrush() : MutedBrush(),
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(0, 0, 8, 0),
                };
                Grid.SetColumn(state, 1);

                var connect = new Button { Content = device.Connected ? "Отключить" : "Подключить" };
                connect.Click += async (_, _) =>
                {
                    var wasConnected = device.Connected;
                    busy = true;
                    status.Text = wasConnected ? "Отключаю…" : "Подключаю…";
                    await SendBtCommandAsync(device.Mac,
                        wasConnected ? BtCommand.Disconnect : BtCommand.Connect);
                    // ждём, пока колонка действительно переключит устройство (1–4 с),
                    // иначе список показывал прежнее состояние
                    var changed = await WaitForChangeAsync(device.Mac, !wasConnected);
                    busy = false;
                    status.Text = changed
                        ? (wasConnected ? "Отключено" : "Подключено")
                        : "Колонка не переключилась: проверьте, что Bluetooth включён на устройстве";
                };
                Grid.SetColumn(connect, 2);

                var forget = new Button { Content = "Забыть" };
                forget.Click += async (_, _) =>
                {
                    status.Text = "Убираю устройство…";
                    await SendBtCommandAsync(device.Mac, BtCommand.Remove);
                    for (var i = 0; i < 5; i++)
                    {
                        await Task.Delay(800);
                        var before = signature;
                        await ReloadAsync();
                        if (signature != before) break;
                    }
                };
                Grid.SetColumn(forget, 3);

                row.Children.Add(name);
                row.Children.Add(state);
                row.Children.Add(connect);
                row.Children.Add(forget);
                list.Children.Add(row);
            }

            var pairing = new Button
            {
                Content = "Режим сопряжения (подключить телефон)",
                HorizontalAlignment = HorizontalAlignment.Stretch,
                Margin = new Thickness(0, 8, 0, 0),
            };
            pairing.Click += async (_, _) =>
            {
                await CommandAsync(Requests.BtDiscoverable);
                ShowNotice("Колонка в режиме сопряжения — подключите телефон в настройках Bluetooth");
            };
            list.Children.Add(pairing);
        }

        async Task ReloadAsync()
        {
            var client = _client;
            if (client == null)
            {
                status.Text = "Нет соединения с колонкой";
                return;
            }
            try
            {
                var raw = await client.RequestRawAsync(Requests.GetPairedBt);
                var devices = Parsers.ParseBtDevices(raw);
                lastDevices = devices;
                Render(devices);
            }
            catch (Exception ex)
            {
                status.Text = "Не удалось прочитать список: " + ex.Message;
            }
        }

        /// <summary>Ждёт, пока в списке появится желаемое состояние устройства.</summary>
        async Task<bool> WaitForChangeAsync(string mac, bool wantConnected)
        {
            for (var i = 0; i < 6; i++)
            {
                await Task.Delay(700);
                await ReloadAsync();
                var device = lastDevices.FirstOrDefault(d => d.Mac.Equals(mac, StringComparison.OrdinalIgnoreCase));
                if (device != null && device.Connected == wantConnected) return true;
            }
            return false;
        }

        dialog.Opened += (_, _) => _ = ReloadAsync();
        await ShowDialogGuardedAsync(dialog);
    }

    /// <summary>Коды op=20 из интеграции sboom_ha: 0 — подключить, 1 — отключить, 2 — забыть.</summary>
    private enum BtCommand
    {
        Connect = 0,
        Disconnect = 1,
        Remove = 2,
    }

    private async Task SendBtCommandAsync(string mac, BtCommand command)
    {
        if (_client == null)
        {
            Program.Log("bt: нет соединения");
            return;
        }
        Program.Log($"bt: {command} {mac}");
        var request = Tlv.Bytes(Proto.OpBtDeviceCommand,
            Tlv.Concat(Tlv.Str(1, mac), Tlv.Int(2, (int)command)));
        await CommandAsync(request);
    }

    private async void OpenBluetoothPairing_Click(object sender, RoutedEventArgs e)
    {
        await CommandAsync(Requests.BtDiscoverable);
        await ShowDialogAsync("Играть с телефона", BuildPhonePlaybackPanel());
    }

    private static StackPanel BuildPhonePlaybackPanel()
    {
        var panel = new StackPanel { Spacing = 10, Width = 340 };
        void Step(string text) => panel.Children.Add(new TextBlock
        {
            Text = text,
            TextWrapping = TextWrapping.Wrap,
            Style = (Style)Application.Current.Resources["BodyTextBlockStyle"],
        });

        Step("1. Колонка уже переведена в режим сопряжения.");
        Step("2. На телефоне откройте «Настройки → Bluetooth» и подключитесь к SberBoom.");
        Step("3. Включите музыку в любом приложении — звук пойдёт через колонку.");
        Step("Подписка Сбера для этого не нужна.");
        return panel;
    }

    // ─────────────────────────── история ───────────────────────────

    private async void OpenHistory_Click(object sender, RoutedEventArgs e) => await HistoryDialogAsync();

    private async Task HistoryDialogAsync()
    {
        var panel = new StackPanel { Spacing = 10 };
        var list = new ListView { Height = 340, SelectionMode = ListViewSelectionMode.Single, IsItemClickEnabled = true };
        panel.Children.Add(list);

        if (_settings.History.Count == 0)
        {
            panel.Children.Add(new TextBlock
            {
                Text = "Пока ничего не играло",
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = MutedBrush(),
            });
        }
        else
        {
            foreach (var entry in _settings.History) list.Items.Add(entry.Line);
        }

        var dialog = new ContentDialog
        {
            XamlRoot = RootGrid.XamlRoot,
            Title = "Недавнее",
            Content = panel,
            CloseButtonText = "Закрыть",
            SecondaryButtonText = "Очистить",
        };

        dialog.SecondaryButtonClick += (_, _) =>
        {
            _settings.History.Clear();
            _settings.Save();
            list.Items.Clear();
        };

        list.ItemClick += async (_, args) =>
        {
            if (args.ClickedItem is not string line) return;
            var index = list.Items.IndexOf(line);
            if (index < 0 || index >= _settings.History.Count) return;
            await PlayDeeplinkAsync(ZvukApi.Deeplink("track", _settings.History[index].TrackId));
            dialog.Hide();
        };

        await ShowDialogGuardedAsync(dialog);
    }

    // ─────────────────────────── диагностика ───────────────────────────

    private async void OpenDiagnostics_Click(object sender, RoutedEventArgs e) => await DiagnosticsDialogAsync();

    private async Task DiagnosticsDialogAsync()
    {
        var panel = new StackPanel { Spacing = 6 };

        void Row(string label, string? value)
        {
            var line = new Grid();
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(150) });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var name = new TextBlock
            {
                Text = label,
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = MutedBrush(),
            };
            var text = new TextBlock
            {
                Text = value ?? "—",
                TextWrapping = TextWrapping.Wrap,
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            };
            Grid.SetColumn(text, 1);
            line.Children.Add(name);
            line.Children.Add(text);
            panel.Children.Add(line);
        }

        var rtt = _client != null ? await _client.EchoAsync(11) : null;
        var device = _state?.Device;

        Row("Колонка", _settings.Host);
        Row("Отклик (эхо op=8)", rtt == null ? "нет ответа" : "ок");
        Row("Сеть", device?.NetworkType);
        Row("Модель", _settings.DeviceModel);
        Row("Прошивка", _settings.DeviceFirmware);
        Row("Подсветка", device?.LedOn switch { true => "включена", false => "выключена", _ => null });
        Row("Яркость LED", device?.LedBrightness?.ToString());
        Row("Будильники", device?.AlarmsCount?.ToString());
        Row("Подписка Звука", device?.SubscriptionStatus);
        Row("Подписка до", device?.SubscriptionUntil);
        Row("Источник", _state?.Track?.Provider);
        Row("Трек", _state?.Track?.Title);
        Row("Токен", _settings.Host != null && _settings.TokenFor(_settings.Host) != null ? "сохранён" : "нет");

        await ShowDialogAsync("Диагностика связи", new ScrollViewer { Content = panel, MaxHeight = 400 });
    }

    // ─────────────────────────── состояние колонки ───────────────────────────

    private async void DevicePanel_Click(object sender, RoutedEventArgs e) => await ShowDeviceDialogAsync();

    private async Task ShowDeviceDialogAsync()
    {
        var panel = new StackPanel { Spacing = 6 };
        void Row(string label, string? value)
        {
            if (string.IsNullOrWhiteSpace(value)) return;
            var line = new Grid();
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(130) });
            line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

            var name = new TextBlock
            {
                Text = label,
                Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
                Foreground = MutedBrush(),
            };
            var text = new TextBlock
            {
                Text = value,
                TextWrapping = TextWrapping.Wrap,
                Style = (Style)Application.Current.Resources["BodyTextBlockStyle"],
            };
            Grid.SetColumn(text, 1);
            line.Children.Add(name);
            line.Children.Add(text);
            panel.Children.Add(line);
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
        Row("Скорость", _state?.Track?.Speed is { } sp ? FormatSpeed(sp) : null);

        await ShowDialogAsync("Колонка", new ScrollViewer { Content = panel, MaxHeight = 380 });
    }
}
