using System;
using System.Text;

namespace SberBoom.WinUI.Protocol;

/// <summary>Сборка WS-конвертов и тел запросов. Порт protocol/Envelope.kt и Requests.kt.</summary>
public static class Requests
{
    public static readonly byte[] GetState = Tlv.EmptyRequest(Proto.OpGetState);
    public static readonly byte[] GetMetadata = Tlv.EmptyRequest(Proto.OpGetMetaData);
    public static readonly byte[] GetQueue = Tlv.EmptyRequest(Proto.OpGetPlayingQueue);
    public static readonly byte[] GetPairedBt = Tlv.EmptyRequest(Proto.OpGetPairedBt);
    public static readonly byte[] BtDiscoverable = Tlv.EmptyRequest(Proto.OpBtDiscoverable);
    public static readonly byte[] KeepAlive = Tlv.EmptyRequest(Proto.OpKeepAlive);
    public static readonly byte[] PairInit = Tlv.EmptyRequest(Proto.OpPinConnect);

    public static byte[] Media(int action) => Tlv.Bytes(Proto.OpMediaCommand, Tlv.Int(1, action));

    public static byte[] Volume(int percent) =>
        Tlv.Bytes(Proto.OpSetVolume, Tlv.Int(1, Math.Clamp(percent, 0, Proto.VolumeMax)));

    public static byte[] Seek(int positionSec) =>
        Tlv.Bytes(Proto.OpSetTrackPos, Tlv.Int(1, Math.Max(0, positionSec)));

    public static byte[] Speed(float rate) =>
        Tlv.Bytes(Proto.OpSetPlaybackSpeed,
            Tlv.Float(1, Math.Clamp(rate, Proto.SpeedMin, Proto.SpeedMax)));

    /// <summary>Обычный конверт запроса: 1=type(2), 2=id, 3=token, 5=data, 6=token_type, 7=client, 10=is_request, 11=client_id.</summary>
    public static byte[] RequestEnvelope(string msgId, byte[] requestData, string? token, string clientName, string clientId)
    {
        var parts = new System.Collections.Generic.List<byte[]>
        {
            Tlv.Int(1, 2),
            Tlv.Str(2, msgId),
        };
        if (!string.IsNullOrEmpty(token)) parts.Add(Tlv.Str(3, token!));
        parts.Add(Tlv.Bytes(5, requestData));
        parts.Add(Tlv.Int(6, Proto.TokenTypePin));
        parts.Add(Tlv.Str(7, clientName));
        parts.Add(Tlv.Int(10, 1));
        parts.Add(Tlv.Str(11, clientId));
        return Tlv.Concat(parts.ToArray());
    }

    /// <summary>ServerAction-конверт: type=1, полезная нагрузка в поле 4.</summary>
    public static byte[] ServerActionEnvelope(string msgId, string bodyJson, string? token,
        string clientName, string clientId)
    {
        var parts = new System.Collections.Generic.List<byte[]>
        {
            Tlv.Int(1, 1),
            Tlv.Str(2, msgId),
        };
        if (!string.IsNullOrEmpty(token)) parts.Add(Tlv.Str(3, token!));
        parts.Add(Tlv.Bytes(4, Tlv.Bytes(1, Encoding.UTF8.GetBytes(bodyJson))));
        parts.Add(Tlv.Int(6, Proto.TokenTypePin));
        parts.Add(Tlv.Str(7, clientName));
        parts.Add(Tlv.Int(10, 1));
        parts.Add(Tlv.Str(11, clientId));
        return Tlv.Concat(parts.ToArray());
    }

    /// <summary>Тело ServerAction: {name, payload, smartAppInfo, type} — как у интеграции.</summary>
    public static string ServerActionJson(string name, string payloadJson) =>
        "{\"name\":" + System.Text.Json.JsonSerializer.Serialize(name) +
        ",\"payload\":" + payloadJson +
        ",\"smartAppInfo\":{\"appVersionId\":\"\",\"applicationId\":\"\",\"frontendEndpoint\":\"\"," +
        "\"frontendType\":\"\",\"projectId\":\"\",\"systemName\":\"\"},\"type\":1}";

    public static string NewMsgId() => Guid.NewGuid().ToString();

    public static string MsgIdOf(byte[] envelope)
    {
        var decoded = Tlv.Decode(envelope);
        return Tlv.Text(decoded, 2) ?? string.Empty;
    }

    /// <summary>URL обложки по id релиза Звука (как в Android-версии).</summary>
    public static string? CoverUrl(string? releaseId) =>
        string.IsNullOrEmpty(releaseId)
            ? null
            : $"{Proto.ZvukImageCdn}?type=release&id={releaseId}&size={Proto.CoverSize}";
}

/// <summary>Ответ колонки, разобранный на статус и полезную нагрузку.</summary>
public sealed class EnvelopeReply
{
    public int? Status { get; init; }
    public System.Collections.Generic.Dictionary<int, object>? Body { get; init; }

    public static EnvelopeReply Parse(byte[] raw)
    {
        var decoded = Tlv.Decode(raw);
        return new EnvelopeReply
        {
            Status = Tlv.Num(decoded, Proto.EnvelopeFieldStatus),
            Body = Tlv.Map(decoded, 5),
        };
    }
}

public sealed class SpeakerException : Exception
{
    public SpeakerException(string message) : base(message) { }
}

public sealed class AuthException : Exception
{
    public AuthException(string message) : base(message) { }
}

public sealed class PairException : Exception
{
    public PairException(string message) : base(message) { }
}
