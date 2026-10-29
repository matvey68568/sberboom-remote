using System;
using System.Collections.Generic;
using System.Text;
using System.Text.Json;

namespace SberBoom.WinUI.Protocol;

public sealed class BtDevice
{
    public string Mac { get; init; } = "";
    public string Name { get; init; } = "";
    public bool Connected { get; init; }

    public string Detail => Connected ? Mac + " · подключено" : Mac;
}

/// <summary>
/// Разбор ответов с повторяющимися тегами (op=19 — список BT-устройств)
/// и очереди (op=17). Обычный Tlv.Decode схлопывает repeated-поля, поэтому
/// здесь используется декодер, сохраняющий сырые payload'ы.
/// </summary>
public static class Parsers
{
    /// <summary>Список спаренных Bluetooth-устройств: envelope[5] → [19] → [1] → {1:mac, 2:name, 3:connected}.</summary>
    public static List<BtDevice> ParseBtDevices(byte[] raw)
    {
        var result = new List<BtDevice>();
        var env = Tlv.DecodeRepeated(raw);
        var body = First(env, 5);
        if (body == null) return result;

        var opField = First(Tlv.DecodeRepeated(body), Proto.OpGetPairedBt);
        if (opField == null) return result;

        var devices = Tlv.DecodeRepeated(opField);
        if (!devices.TryGetValue(1, out var list)) return result;

        foreach (var payload in list)
        {
            var fields = Tlv.Decode(payload);
            var mac = Tlv.Text(fields, 1);
            if (string.IsNullOrEmpty(mac)) continue;
            result.Add(new BtDevice
            {
                Mac = mac!,
                Name = Tlv.Text(fields, 2) ?? "",
                Connected = (Tlv.Num(fields, 3) ?? 0) != 0,
            });
        }
        return result;
    }

    /// <summary>
    /// Очередь (op=17): поле 5 вложенного сообщения — JSON-массив
    /// `[{"trackId":123,"explicit":false}, …]`. Если TLV-декодер отдал payload
    /// строкой, ищем сбалансированный массив прямо в тексте кадра.
    /// </summary>
    public static List<string> ParseQueueTrackIds(byte[] raw)
    {
        var ids = new List<string>();

        var text = Tlv.Text(Tlv.Decode(raw), 5);
        var json = text != null ? FindArray(text, "trackId") : null;
        json ??= FindArray(Encoding.UTF8.GetString(raw), "trackId");
        json ??= FindArray(Encoding.Latin1.GetString(raw), "trackId");
        if (json == null) return ids;

        try
        {
            using var doc = JsonDocument.Parse(json);
            foreach (var item in doc.RootElement.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Object) continue;
                if (item.TryGetProperty("trackId", out var id))
                {
                    var value = id.ValueKind == JsonValueKind.Number ? id.GetRawText() : id.GetString();
                    if (!string.IsNullOrEmpty(value)) ids.Add(value!);
                }
            }
        }
        catch (JsonException)
        {
            // очередь пришла в неизвестном формате — показываем пустую
        }
        return ids;
    }

    private static byte[]? First(Dictionary<int, List<byte[]>> map, int tag) =>
        map.TryGetValue(tag, out var list) && list.Count > 0 ? list[0] : null;

    /// <summary>Первый сбалансированный JSON-массив, содержащий указанный ключ.</summary>
    private static string? FindArray(string text, string key)
    {
        var from = 0;
        while (true)
        {
            var start = text.IndexOf('[', from);
            if (start < 0) return null;

            var depth = 0;
            var inString = false;
            var escape = false;
            for (var i = start; i < text.Length; i++)
            {
                var ch = text[i];
                if (inString)
                {
                    if (escape) escape = false;
                    else if (ch == '\\') escape = true;
                    else if (ch == '"') inString = false;
                    continue;
                }
                switch (ch)
                {
                    case '"': inString = true; break;
                    case '[': depth++; break;
                    case ']':
                        depth--;
                        if (depth == 0)
                        {
                            var candidate = text.Substring(start, i - start + 1);
                            if (candidate.Contains(key, StringComparison.Ordinal)) return candidate;
                            from = i + 1;
                            goto next;
                        }
                        break;
                }
            }
            return null;
        next: ;
        }
    }
}
