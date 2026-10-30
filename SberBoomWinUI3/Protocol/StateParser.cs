using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;

namespace SberBoom.WinUI.Protocol;

public enum Source { Unknown, Music, Radio, Bluetooth }

public sealed class Track
{
    public string? Title { get; init; }
    public List<string> Artists { get; init; } = new();
    public string? Album { get; init; }
    public string? TrackId { get; init; }
    public string? ReleaseId { get; init; }
    public string? PlaylistTitle { get; init; }
    public string? PlaylistType { get; init; }
    public Source Source { get; init; } = Source.Unknown;
    public string? StationName { get; init; }
    public string? Provider { get; init; }
    public int? DurationSec { get; init; }
    public int PositionSec { get; init; }
    public bool Playing { get; init; }
    public bool Shuffle { get; init; }
    public string? Repeat { get; init; }
    public bool Explicit { get; init; }
    public bool Liked { get; init; }
    public bool Buffering { get; init; }
    public float? Speed { get; init; }
    public long ReceivedAtMs { get; init; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public string ArtistLine => string.Join(", ", Artists);

    /// <summary>Позиция с учётом времени, прошедшего с момента получения состояния.</summary>
    public int LivePositionSec(long? nowMs = null)
    {
        if (!Playing) return PositionSec;
        var now = nowMs ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var elapsed = (int)((now - ReceivedAtMs) / 1000);
        var pos = PositionSec + elapsed;
        return DurationSec is > 0 ? Math.Min(pos, DurationSec.Value) : pos;
    }

    public string? CoverUrl => Requests.CoverUrl(ReleaseId);
}

public sealed class DeviceState
{
    public string? Model { get; init; }
    public string? Firmware { get; init; }
    public string? Vendor { get; init; }
    public string? Serial { get; init; }
    public string? Name { get; init; }
    public string? NetworkType { get; init; }
    public string? Ip { get; init; }
    public int? LedBrightness { get; init; }
    public bool? LedOn { get; init; }
    public int? Volume { get; init; }
    public bool? Muted { get; init; }
    public int? AlarmsCount { get; init; }
    public string? SubscriptionStatus { get; init; }
    public string? SubscriptionUntil { get; init; }
}

public sealed class SpeakerState
{
    public Track? Track { get; init; }
    public int Volume { get; init; }
    public bool Muted { get; init; }
    public DeviceState Device { get; init; } = new();
}

/// <summary>
/// Разбор состояния из op=12. Структура (проверена на реальном кадре):
///   volume{muted,percent}
///   background_apps[].state.player{playing,shuffle,repeatType,playbackSpeedRate,
///       info{title,trackId,artists[{id,name}],releases[{id,title}],playlistTitle,
///            playlistType,provider,explicit,hasLyrics,like,total}}
/// Активный плеер — тот, где есть info.trackId (приоритет играющему).
/// </summary>
public static class StateParser
{
    public static SpeakerState? Parse(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return null;
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(json);
        }
        catch (JsonException)
        {
            return null;
        }

        using (doc)
        {
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;

            var (player, info) = FindPlayer(root);
            var track = info is { } i ? BuildTrack(player, i) : null;

            var volume = 0;
            var muted = false;
            if (root.TryGetProperty("volume", out var vol) && vol.ValueKind == JsonValueKind.Object)
            {
                volume = GetInt(vol, "percent") ?? 0;
                muted = GetBool(vol, "muted") ?? false;
            }

            return new SpeakerState
            {
                Track = track,
                Volume = volume,
                Muted = muted,
                Device = BuildDevice(root),
            };
        }
    }

    private static (JsonElement? player, JsonElement? info) FindPlayer(JsonElement root)
    {
        // У Bluetooth-источника нет trackId, поэтому «играющее приложение» важнее
        // наличия идентификатора: иначе окно показывало трек Звука, а не то,
        // что реально играет с телефона.
        JsonElement? playingWithInfo = null, playingInfo = null;
        JsonElement? withTrack = null, withTrackInfo = null;
        JsonElement? firstPlayer = null, firstInfo = null;

        if (!root.TryGetProperty("background_apps", out var apps) || apps.ValueKind != JsonValueKind.Array)
            return (null, null);

        JsonElement? freshestPlayer = null, freshestInfo = null;
        var freshestAt = -1L;

        foreach (var app in apps.EnumerateArray())
        {
            if (!app.TryGetProperty("state", out var state) || state.ValueKind != JsonValueKind.Object) continue;
            if (!state.TryGetProperty("player", out var player) || player.ValueKind != JsonValueKind.Object) continue;

            firstPlayer ??= player;
            if (player.TryGetProperty("info", out var info) && info.ValueKind == JsonValueKind.Object)
            {
                firstInfo ??= info;
                var hasTitle = !string.IsNullOrWhiteSpace(GetString(info, "title"));
                if (hasTitle && playingWithInfo == null && GetBool(player, "playing") == true)
                {
                    playingWithInfo = player;
                    playingInfo = info;
                }
                // Свежесть состояния: при паузе у обоих приложений играющим считается
                // то, чьё состояние обновилось последним (иначе показывался трек Звука).
                var at = GetLong(player, "stateChangedTimestamp") ?? 0;
                if (hasTitle && at > freshestAt)
                {
                    freshestAt = at;
                    freshestPlayer = player;
                    freshestInfo = info;
                }
                if (GetString(info, "trackId") is { Length: > 0 })
                {
                    withTrack ??= player;
                    withTrackInfo ??= info;
                    if (GetBool(player, "playing") == true)
                        return (player, info);
                }
            }
        }

        if (playingWithInfo != null) return (playingWithInfo, playingInfo);
        if (freshestPlayer != null) return (freshestPlayer, freshestInfo);
        return withTrack != null ? (withTrack, withTrackInfo) : (firstPlayer, firstInfo);
    }

    private static Track BuildTrack(JsonElement? player, JsonElement info)
    {
        var artists = new List<string>();
        if (info.TryGetProperty("artists", out var arr) && arr.ValueKind == JsonValueKind.Array)
        {
            foreach (var a in arr.EnumerateArray())
            {
                var name = GetString(a, "name");
                if (!string.IsNullOrWhiteSpace(name)) artists.Add(name!);
            }
        }

        string? releaseId = null;
        string? album = null;
        if (info.TryGetProperty("releases", out var rel) && rel.ValueKind == JsonValueKind.Array)
        {
            foreach (var r in rel.EnumerateArray())
            {
                releaseId ??= GetString(r, "id");
                album ??= GetString(r, "title") ?? GetString(r, "name");
            }
        }

        var mode = player is { } pl ? GetString(pl, "mode") : null;
        var provider = GetString(info, "provider");
        var playlistTitle = GetString(info, "playlistTitle");
        var source = provider == "bluetooth"
            ? Source.Bluetooth
            : mode == "radio" || GetString(info, "stationName") != null
                ? Source.Radio
                : provider == "zvuk" || GetString(info, "trackId") != null
                    ? Source.Music
                    : Source.Unknown;

        var duration = player is { } p1 ? GetInt(p1, "duration") : null;
        duration ??= GetInt(info, "total");
        var position = player is { } p2 ? GetInt(p2, "position") : null;

        return new Track
        {
            Title = GetString(info, "title"),
            Artists = artists,
            Album = album,
            TrackId = GetString(info, "trackId"),
            ReleaseId = releaseId,
            PlaylistTitle = playlistTitle,
            PlaylistType = GetString(info, "playlistType"),
            Source = source,
            StationName = GetString(info, "stationName"),
            Provider = provider,
            DurationSec = duration is > 0 ? duration : null,
            PositionSec = position ?? 0,
            Playing = player is { } p3 && GetBool(p3, "playing") == true,
            Shuffle = player is { } p4 && GetBool(p4, "shuffle") == true,
            Repeat = player is { } p5 ? GetString(p5, "repeatType") : null,
            Explicit = GetBool(info, "explicit") == true,
            Liked = GetBool(info, "like") == true,
            Buffering = player is { } p6 && GetBool(p6, "buffering") == true,
            Speed = player is { } p7 ? GetFloat(p7, "playbackSpeedRate") : null,
        };
    }

    private static DeviceState BuildDevice(JsonElement root)
    {
        var device = new DeviceState();
        string? networkType = null, ip = null;
        if (root.TryGetProperty("network", out var net) && net.ValueKind == JsonValueKind.Object)
        {
            networkType = GetString(net, "connection_type");
            ip = GetString(net, "ip");
        }

        int? brightness = null;
        bool? ledOn = null;
        if (root.TryGetProperty("capabilities_state", out var cap) && cap.ValueKind == JsonValueKind.Object &&
            cap.TryGetProperty("led_display", out var led) && led.ValueKind == JsonValueKind.Object)
        {
            brightness = GetInt(led, "brightness");
            ledOn = GetBool(led, "turned_on");
        }

        int? alarms = null;
        if (root.TryGetProperty("alarm", out var alarm) && alarm.ValueKind == JsonValueKind.Object)
        {
            alarms = GetInt(alarm, "alarmsCounter");
            if (alarms == null && alarm.TryGetProperty("alarms", out var a) && a.ValueKind == JsonValueKind.Array)
                alarms = a.GetArrayLength();
        }

        return new DeviceState
        {
            NetworkType = networkType,
            Ip = ip,
            LedBrightness = brightness,
            LedOn = ledOn,
            AlarmsCount = alarms,
            Model = device.Model,
        };
    }

    private static string? GetString(JsonElement obj, string name)
    {
        if (!obj.TryGetProperty(name, out var v)) return null;
        return v.ValueKind switch
        {
            JsonValueKind.String => v.GetString(),
            JsonValueKind.Number => v.GetRawText(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            _ => null,
        };
    }

    private static int? GetInt(JsonElement obj, string name)
    {
        if (!obj.TryGetProperty(name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var i)) return i;
        if (v.ValueKind == JsonValueKind.String && int.TryParse(v.GetString(), out var s)) return s;
        return null;
    }

    private static bool? GetBool(JsonElement obj, string name)
    {
        if (!obj.TryGetProperty(name, out var v)) return null;
        return v.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null,
        };
    }

    private static float? GetFloat(JsonElement obj, string name)
    {
        if (!obj.TryGetProperty(name, out var v)) return null;
        if (v.ValueKind == JsonValueKind.Number && v.TryGetSingle(out var f)) return f;
        return null;
    }
}
