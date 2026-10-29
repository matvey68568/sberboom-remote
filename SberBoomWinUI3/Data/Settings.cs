using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace SberBoom.WinUI.Data;

public sealed class HistoryEntry
{
    public string TrackId { get; set; } = "";
    public string Title { get; set; } = "";
    public string? Artist { get; set; }
    public string? Cover { get; set; }
    public long AtMs { get; set; }

    public string Line => string.IsNullOrWhiteSpace(Artist) ? Title : Title + " — " + Artist;

    public override string ToString() => Line;
}

public sealed class KnownSpeaker
{
    public string Host { get; set; } = "";
    public int Port { get; set; } = 20000;
    public string? Name { get; set; }
    public string? Model { get; set; }
    public string? Firmware { get; set; }

    public string Detail =>
        string.Join(" · ", new[] { Host, Model, Firmware }.Where(s => !string.IsNullOrWhiteSpace(s)));
}

/// <summary>
/// Настройки в %APPDATA%\SberBoomRemote\settings.json — формат ключей повторяет
/// Android/Compose-версию (host, port, tokens, client_id, light_theme, speakers).
/// </summary>
public sealed class Settings
{
    public const string DefaultClientName = "SberBoom Remote";

    public string? Host { get; set; }
    public int Port { get; set; } = 20000;
    public string? DeviceName { get; set; }
    public string? DeviceModel { get; set; }
    public string? DeviceFirmware { get; set; }
    public string ClientId { get; set; } = Guid.NewGuid().ToString();
    public string ClientName { get; set; } = DefaultClientName;
    public bool LightTheme { get; set; }
    public Dictionary<string, string> Tokens { get; set; } = new();
    public List<HistoryEntry> History { get; set; } = new();
    public long? SleepUntilMs { get; set; }
    public List<KnownSpeaker> Speakers { get; set; } = new();

    [JsonIgnore]
    private static string Dir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SberBoomRemote");

    [JsonIgnore]
    private static string FilePath => Path.Combine(Dir, "settings.json");

    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var json = File.ReadAllText(FilePath);
                var loaded = JsonSerializer.Deserialize<Settings>(json, Options);
                if (loaded != null)
                {
                    if (string.IsNullOrEmpty(loaded.ClientId)) loaded.ClientId = Guid.NewGuid().ToString();
                    return loaded;
                }
            }
        }
        catch
        {
            // повреждённый файл — начинаем с чистых настроек
        }
        return new Settings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Options));
        }
        catch
        {
            // настройки не критичны для работы
        }
    }

    public string? TokenFor(string host) =>
        Tokens.TryGetValue(host, out var token) ? token : null;

    public void SetToken(string host, string? token)
    {
        if (string.IsNullOrEmpty(token)) Tokens.Remove(host);
        else Tokens[host] = token!;
        Save();
    }

    public void RememberSpeaker(string host, int port, string? name, string? model, string? firmware)
    {
        Speakers.RemoveAll(s => s.Host == host);
        Speakers.Insert(0, new KnownSpeaker { Host = host, Port = port, Name = name, Model = model, Firmware = firmware });
        if (Speakers.Count > 8) Speakers.RemoveRange(8, Speakers.Count - 8);
        Host = host;
        Port = port;
        DeviceName = name;
        DeviceModel = model;
        DeviceFirmware = firmware;
        Save();
    }

    /// <summary>Недавно игравшее: до 40 записей, без повторов подряд.</summary>
    public void PushHistory(string? trackId, string? title, string? artist, string? cover)
    {
        if (string.IsNullOrWhiteSpace(trackId) || string.IsNullOrWhiteSpace(title)) return;
        History.RemoveAll(h => h.TrackId == trackId);
        History.Insert(0, new HistoryEntry
        {
            TrackId = trackId!,
            Title = title!,
            Artist = artist,
            Cover = cover,
            AtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        });
        if (History.Count > 40) History.RemoveRange(40, History.Count - 40);
        Save();
    }

    public void ForgetSpeaker(string host)
    {
        Tokens.Remove(host);
        Speakers.RemoveAll(s => s.Host == host);
        if (Host == host) Host = null;
        Save();
    }
}
