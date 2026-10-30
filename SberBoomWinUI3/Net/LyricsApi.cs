using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace SberBoom.WinUI.Net;

public sealed class LrcLine
{
    public float TimeSec { get; init; }
    public string Text { get; init; } = "";
}

/// <summary>
/// Тексты песен с lrclib.net (тот же источник, что в Android-версии):
/// syncedLyrics в формате LRC разбирается в строки с таймкодами.
/// </summary>
public static class LyricsApi
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(12) };

    /// <summary>Текст без таймкодов — показывается, когда синхронизированного нет.</summary>
    public static string? LastPlain { get; private set; }

    /// <summary>Инструментальная композиция — текста не будет по определению.</summary>
    public static bool LastInstrumental { get; private set; }

    public static async Task<List<LrcLine>> GetAsync(string? artist, string? title, string? album, int? durationSec)
    {
        LastPlain = null;
        LastInstrumental = false;
        if (string.IsNullOrWhiteSpace(artist) || string.IsNullOrWhiteSpace(title)) return new List<LrcLine>();

        // Первый заход — с альбомом и длительностью, второй — только название и
        // исполнитель. У Bluetooth-трека альбом и длительность приходят от телефона
        // и часто не совпадают с базой: одна попытка давала 404 и «текст не найден».
        foreach (var strict in new[] { true, false })
        {
            var url = "https://lrclib.net/api/get"
                      + $"?artist_name={Uri.EscapeDataString(artist)}"
                      + $"&track_name={Uri.EscapeDataString(title)}"
                      + (strict && !string.IsNullOrWhiteSpace(album) ? $"&album_name={Uri.EscapeDataString(album!)}" : "")
                      + (strict && durationSec is > 0 ? $"&duration={durationSec}" : "");

            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, url);
                request.Headers.UserAgent.ParseAdd("SberBoomRemote/1.0 (+https://github.com/matvey68568/sberboom-remote)");
                using var response = await Http.SendAsync(request).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode) continue;

                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                using var doc = JsonDocument.Parse(body);
                var root = doc.RootElement;

                if (root.TryGetProperty("instrumental", out var instr) && instr.ValueKind == JsonValueKind.True)
                {
                    LastInstrumental = true;
                    return new List<LrcLine>();
                }

                if (root.TryGetProperty("plainLyrics", out var plain) && plain.ValueKind == JsonValueKind.String)
                {
                    LastPlain = plain.GetString()?.Trim();
                }

                if (root.TryGetProperty("syncedLyrics", out var synced) &&
                    synced.ValueKind == JsonValueKind.String &&
                    !string.IsNullOrWhiteSpace(synced.GetString()))
                {
                    var lines = ParseLrc(synced.GetString()!);
                    if (lines.Count > 0)
                    {
                        SberBoom.WinUI.Program.Log($"lyrics: {lines.Count} строк (строгий поиск: {strict})");
                        return lines;
                    }
                }

                // Есть только plain — годится как резерв, но синхронизированный
                // вариант ищем ещё раз без альбома.
                if (!string.IsNullOrWhiteSpace(LastPlain) && !strict) return new List<LrcLine>();
            }
            catch (Exception ex)
            {
                SberBoom.WinUI.Program.Log("lyrics: " + ex.Message);
            }
        }

        return new List<LrcLine>();
    }

    /// <summary>Поправка на задержку позиции: строка показывается чуть раньше звука.</summary>
    public const float LeadSec = 0.35f;

    private static readonly Regex TimeTag = new(@"\[(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?\]", RegexOptions.Compiled);

    /// <summary>Разбор LRC: строки с таймкодами, пустые пропускаются.</summary>
    public static List<LrcLine> ParseLrc(string lrc)
    {
        var lines = new List<LrcLine>();
        foreach (var raw in lrc.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0) continue;

            var matches = TimeTag.Matches(line);
            if (matches.Count == 0) continue;

            var text = TimeTag.Replace(line, "").Trim();
            if (text.Length == 0) continue;

            foreach (Match m in matches)
            {
                var minutes = int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture);
                var seconds = int.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture);
                var fraction = 0f;
                if (m.Groups[3].Success)
                {
                    var digits = m.Groups[3].Value.PadRight(3, '0');
                    fraction = int.Parse(digits, CultureInfo.InvariantCulture) / 1000f;
                }
                lines.Add(new LrcLine { TimeSec = minutes * 60 + seconds + fraction, Text = text });
            }
        }

        lines.Sort((a, b) => a.TimeSec.CompareTo(b.TimeSec));
        return lines;
    }

    /// <summary>Индекс активной строки по позиции трека (-1, если ещё не началось).</summary>
    public static int ActiveIndex(List<LrcLine> lines, float positionSec, float leadSec = LeadSec)
    {
        var index = -1;
        for (var i = 0; i < lines.Count; i++)
        {
            if (lines[i].TimeSec <= positionSec + leadSec) index = i;
            else break;
        }
        return index;
    }
}
