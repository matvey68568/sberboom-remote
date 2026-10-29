using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace SberBoom.WinUI.Protocol;

/// <summary>
/// TLV-кодек колонки (Google proto-wire без схемы): varint, length-delimited,
/// fixed32. Порт shared/protocol/Tlv.kt — поведение совпадает байт-в-байт,
/// чтобы кадры приложения были идентичны кадрам интеграции HA.
/// </summary>
public static class Tlv
{
    public const int VarintKind = 0;
    public const int LenKind = 2;
    public const int Fixed32Kind = 5;

    public static byte[] Varint(ulong value)
    {
        using var ms = new MemoryStream(10);
        var n = value;
        while (n > 0x7F)
        {
            ms.WriteByte((byte)((n & 0x7F) | 0x80));
            n >>= 7;
        }
        ms.WriteByte((byte)(n & 0x7F));
        return ms.ToArray();
    }

    public static byte[] Field(int tag, int kind, byte[] payload)
    {
        var key = Varint(((ulong)tag << 3) | (ulong)kind);
        using var ms = new MemoryStream();
        ms.Write(key);
        if (kind == LenKind)
        {
            ms.Write(Varint((ulong)payload.Length));
        }
        ms.Write(payload);
        return ms.ToArray();
    }

    public static byte[] Int(int tag, int value) => Field(tag, VarintKind, Varint((ulong)value));

    public static byte[] Bytes(int tag, byte[] value) => Field(tag, LenKind, value);

    public static byte[] Str(int tag, string value) => Field(tag, LenKind, Encoding.UTF8.GetBytes(value));

    public static byte[] Float(int tag, float value)
    {
        var buf = new byte[4];
        BitConverter.TryWriteBytes(buf, value);
        if (!BitConverter.IsLittleEndian) Array.Reverse(buf);
        return Field(tag, Fixed32Kind, buf);
    }

    /// <summary>Пустой запрос: field(op, 2, field(1, 2, "")) — как в интеграции.</summary>
    public static byte[] EmptyRequest(int op) => Bytes(op, Bytes(1, Array.Empty<byte>()));

    public static byte[] Concat(params byte[][] parts)
    {
        using var ms = new MemoryStream();
        foreach (var p in parts) ms.Write(p);
        return ms.ToArray();
    }

    /// <summary>Рекурсивный декодер: varint → long, length-delim → string | map | hex, fixed32 → float.</summary>
    public static Dictionary<int, object> Decode(byte[] data)
    {
        var result = new Dictionary<int, object>();
        var i = 0;
        var n = data.Length;
        while (i < n)
        {
            var keyRes = ReadVarint(data, i);
            if (keyRes == null) return result;
            var key = keyRes.Value.value;
            i = keyRes.Value.next;
            var tag = (int)(key >> 3);
            var kind = (int)(key & 0x7);

            switch (kind)
            {
                case VarintKind:
                {
                    var v = ReadVarint(data, i);
                    if (v == null) return result;
                    result[tag] = (long)v.Value.value;
                    i = v.Value.next;
                    break;
                }
                case LenKind:
                {
                    var lenRes = ReadVarint(data, i);
                    if (lenRes == null) return result;
                    var len = (int)lenRes.Value.value;
                    i = lenRes.Value.next;
                    if (len < 0 || i + len > n) return result;
                    var payload = new byte[len];
                    Array.Copy(data, i, payload, 0, len);
                    i += len;

                    var text = StrictUtf8(payload);
                    if (text != null && IsPrintable(text))
                    {
                        result[tag] = text;
                        continue;
                    }
                    var nested = Decode(payload);
                    result[tag] = nested.Count > 0 ? nested : ToHex(payload);
                    break;
                }
                case Fixed32Kind:
                {
                    if (i + 4 > n) return result;
                    result[tag] = BitConverter.ToSingle(data, i);
                    i += 4;
                    break;
                }
                default:
                    return result;
            }
        }
        return result;
    }

    public static (ulong value, int next)? ReadVarint(byte[] data, int start)
    {
        ulong value = 0;
        var shift = 0;
        var pos = start;
        while (true)
        {
            if (pos >= data.Length) return null;
            var b = data[pos];
            pos++;
            value |= (ulong)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) return (value, pos);
            shift += 7;
            if (shift > 63) return null;
        }
    }

    private static string? StrictUtf8(byte[] payload)
    {
        try
        {
            return new UTF8Encoding(false, throwOnInvalidBytes: true).GetString(payload);
        }
        catch
        {
            return null;
        }
    }

    private static bool IsPrintable(string s)
    {
        foreach (var ch in s)
        {
            if (char.IsControl(ch) && ch != '\n' && ch != '\t') return false;
        }
        return true;
    }

    public static string ToHex(byte[] data) => Convert.ToHexString(data).ToLowerInvariant();

    /// <summary>
    /// Декодер с сохранением повторяющихся тегов (proto `repeated`): значения
    /// возвращаются сырыми payload'ами. Нужен для op=19 (список BT-устройств),
    /// где Tlv.Decode схлопнул бы несколько сообщений в одно.
    /// </summary>
    public static Dictionary<int, List<byte[]>> DecodeRepeated(byte[] data)
    {
        var result = new Dictionary<int, List<byte[]>>();
        var i = 0;
        var n = data.Length;
        while (i < n)
        {
            var keyRes = ReadVarint(data, i);
            if (keyRes == null) return result;
            var key = keyRes.Value.value;
            i = keyRes.Value.next;
            var tag = (int)(key >> 3);

            switch ((int)(key & 0x7))
            {
                case VarintKind:
                {
                    var v = ReadVarint(data, i);
                    if (v == null) return result;
                    Add(result, tag, Varint(v.Value.value));
                    i = v.Value.next;
                    break;
                }
                case LenKind:
                {
                    var lenRes = ReadVarint(data, i);
                    if (lenRes == null) return result;
                    var len = (int)lenRes.Value.value;
                    i = lenRes.Value.next;
                    if (len < 0 || i + len > n) return result;
                    var payload = new byte[len];
                    Array.Copy(data, i, payload, 0, len);
                    i += len;
                    Add(result, tag, payload);
                    break;
                }
                case Fixed32Kind:
                {
                    if (i + 4 > n) return result;
                    var payload = new byte[4];
                    Array.Copy(data, i, payload, 0, 4);
                    Add(result, tag, payload);
                    i += 4;
                    break;
                }
                default:
                    return result;
            }
        }
        return result;
    }

    private static void Add(Dictionary<int, List<byte[]>> map, int tag, byte[] payload)
    {
        if (!map.TryGetValue(tag, out var list))
        {
            list = new List<byte[]>();
            map[tag] = list;
        }
        list.Add(payload);
    }

    /// <summary>Достать вложенный словарь по тегу (null, если тега нет или это не сообщение).</summary>
    public static Dictionary<int, object>? Map(Dictionary<int, object> src, int tag) =>
        src.TryGetValue(tag, out var v) ? v as Dictionary<int, object> : null;

    /// <summary>Число по тегу (int хранится как long).</summary>
    public static int? Num(Dictionary<int, object> src, int tag) =>
        src.TryGetValue(tag, out var v) ? v switch
        {
            long l => (int)l,
            int i => i,
            _ => null,
        } : null;

    /// <summary>Строка по тегу; непечатаемые/бинарные значения возвращаются как hex.</summary>
    public static string? Text(Dictionary<int, object> src, int tag) =>
        src.TryGetValue(tag, out var v) ? v as string : null;

    /// <summary>Байты поля: строка → UTF-8, вложенное сообщение → исходные байты недоступны.</summary>
    public static byte[]? RawText(Dictionary<int, object> src, int tag) =>
        src.TryGetValue(tag, out var v) && v is string s ? Encoding.UTF8.GetBytes(s) : null;
}
