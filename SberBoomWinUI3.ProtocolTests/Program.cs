using System;
using System.Threading.Tasks;
using SberBoom.WinUI.Net;
using SberBoom.WinUI.Protocol;

namespace SberBoom.ProtocolTests;

/// <summary>
/// Живая проверка протокола C#-реализации на настоящей колонке:
///   dotnet run -- 192.168.0.178 <token> [clientId]
/// Печатает состояние, очередь и замер RTT через эхо op=8.
/// </summary>
internal static class Program
{
    private static string Trunc(object? v) => v switch
    {
        string s => s.Length > 120 ? s[..120] + "…" : s,
        System.Collections.Generic.Dictionary<int, object> d => "map(" + string.Join(",", d.Keys) + ")",
        _ => v?.ToString() ?? "null",
    };

    private static async Task<int> Main(string[] args)
    {
        if (args.Length < 2)
        {
            Console.WriteLine("usage: dotnet run -- <host> <token> [clientId]");
            return 2;
        }

        var host = args[0];
        var token = args[1];
        var clientId = args.Length > 2 ? args[2] : "f7f13afa-ef20-4d73-aca0-db9eb871cb88";

        await using var client = new SpeakerClient(host, Proto.DefaultPort, token, "SberBoom Remote", clientId);
        try
        {
            await client.ConnectAsync();
            Console.WriteLine($"connect: ok ({host}:{Proto.DefaultPort})");

            var echo = await client.EchoAsync(7);
            Console.WriteLine($"echo op=8: {echo}");

            var dbg = await client.RequestAsync(Requests.GetState);
            Console.WriteLine("state body keys: " + (dbg.Body == null ? "нет" : string.Join(",", dbg.Body.Keys)));
            if (dbg.Body != null)
            {
                foreach (var kv in dbg.Body)
                {
                    var v = kv.Value;
                    Console.WriteLine($"  [{kv.Key}] {v?.GetType().Name}: {Trunc(v)}");
                }
            }

            var state = await client.GetStateAsync();
            if (state == null)
            {
                Console.WriteLine("state: пустой ответ");
                return 1;
            }

            var t = state.Track;
            Console.WriteLine($"volume: {state.Volume}% muted={state.Muted}");
            Console.WriteLine($"device: led={state.Device.LedOn} brightness={state.Device.LedBrightness} alarms={state.Device.AlarmsCount} net={state.Device.NetworkType}");
            if (t == null)
            {
                Console.WriteLine("track: нет данных");
            }
            else
            {
                Console.WriteLine($"track: \"{t.Title}\" — {t.ArtistLine} / {t.Album}");
                Console.WriteLine($"       playing={t.Playing} pos={t.PositionSec}/{t.DurationSec} shuffle={t.Shuffle} repeat={t.Repeat} liked={t.Liked} speed={t.Speed}");
                Console.WriteLine($"       ids: trackId={t.TrackId} releaseId={t.ReleaseId} source={t.Source}");
                Console.WriteLine($"       cover: {t.CoverUrl}");
            }

            var queueText = await client.GetQueueJsonAsync();
            Console.WriteLine($"queue: {(queueText == null ? "нет" : queueText.Length + " символов JSON")}");
            return 0;
        }
        catch (AuthException)
        {
            Console.WriteLine("auth: токен отозван — нужно сопряжение (нажать «+» на колонке)");
            return 3;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"error: {ex.GetType().Name}: {ex.Message}");
            return 1;
        }
    }
}
