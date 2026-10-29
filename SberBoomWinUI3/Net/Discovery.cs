using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace SberBoom.WinUI.Net;

public sealed class FoundSpeaker
{
    public string Host { get; init; } = "";
    public int Port { get; init; } = Protocol.Proto.DefaultPort;
    public string? Name { get; init; }
    public string? Model { get; init; }
    public string? Firmware { get; init; }

    public string Detail => string.Join(" · ", new[]
    {
        Host + ":" + Port,
        Model,
        Firmware,
    }.Where(s => !string.IsNullOrWhiteSpace(s)));
}

/// <summary>
/// Поиск колонок в локальной сети: TCP-скан подсети по порту 20000 и
/// mDNS-запрос `_staros._tcp.local`. Порт net/Discovery.kt (Android/Desktop).
/// </summary>
public static class Discovery
{
    public static async Task<List<FoundSpeaker>> ScanAsync(int timeoutMs = 4000, CancellationToken ct = default)
    {
        var found = new Dictionary<string, FoundSpeaker>();

        var mdns = await QueryMdnsAsync(Math.Min(1500, timeoutMs), ct).ConfigureAwait(false);
        foreach (var host in mdns) found[host] = new FoundSpeaker { Host = host };

        var scanTask = TcpScanAsync(timeoutMs, ct);
        var results = await scanTask.ConfigureAwait(false);
        foreach (var host in results)
        {
            if (!found.ContainsKey(host)) found[host] = new FoundSpeaker { Host = host };
        }

        return new List<FoundSpeaker>(found.Values);
    }

    /// <summary>Проверить один адрес: открыт ли порт 20000.</summary>
    public static async Task<bool> ProbeAsync(string host, int port = Protocol.Proto.DefaultPort,
        int timeoutMs = 800, CancellationToken ct = default)
    {
        try
        {
            using var client = new TcpClient();
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(timeoutMs);
            await client.ConnectAsync(host, port, cts.Token).ConfigureAwait(false);
            return client.Connected;
        }
        catch
        {
            return false;
        }
    }

    private static async Task<List<string>> TcpScanAsync(int timeoutMs, CancellationToken ct)
    {
        var result = new List<string>();
        var prefix = LocalPrefix();
        if (prefix == null) return result;

        using var gate = new SemaphoreSlim(48);
        var tasks = new List<Task>();
        for (var i = 1; i < 255; i++)
        {
            var host = prefix + i;
            await gate.WaitAsync(ct).ConfigureAwait(false);
            tasks.Add(Task.Run(async () =>
            {
                try
                {
                    if (await ProbeAsync(host, ct: ct).ConfigureAwait(false))
                    {
                        lock (result) result.Add(host);
                    }
                }
                finally
                {
                    gate.Release();
                }
            }, ct));
        }

        try
        {
            await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromMilliseconds(timeoutMs + 2000), ct).ConfigureAwait(false);
        }
        catch
        {
            // таймаут скана — отдаём то, что успели найти
        }
        return result;
    }

    /// <summary>Префикс подсети "192.168.0." для текущего адреса машины.</summary>
    private static string? LocalPrefix()
    {
        try
        {
            foreach (var address in Dns.GetHostAddresses(Dns.GetHostName()))
            {
                if (address.AddressFamily != AddressFamily.InterNetwork) continue;
                var bytes = address.GetAddressBytes();
                if (bytes[0] == 127) continue;
                return $"{bytes[0]}.{bytes[1]}.{bytes[2]}.";
            }
        }
        catch
        {
            // нет сети — скан не имеет смысла
        }
        return null;
    }

    /// <summary>
    /// Минимальный mDNS-запрос (PTR `_staros._tcp.local`) с разбором A-записей ответов.
    /// Zeroconf-библиотека не тянется, а нужен один запрос.
    /// </summary>
    private static async Task<List<string>> QueryMdnsAsync(int timeoutMs, CancellationToken ct)
    {
        var hosts = new List<string>();
        try
        {
            using var udp = new UdpClient();
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, 0));

            var query = BuildPtrQuery("_staros._tcp.local");
            var multicast = new IPEndPoint(IPAddress.Parse("224.0.0.251"), 5353);
            await udp.SendAsync(query, query.Length, multicast).ConfigureAwait(false);

            var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTime.UtcNow < deadline)
            {
                var remaining = deadline - DateTime.UtcNow;
                if (remaining <= TimeSpan.Zero) break;

                using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                cts.CancelAfter(remaining);
                UdpReceiveResult packet;
                try
                {
                    packet = await udp.ReceiveAsync(cts.Token).ConfigureAwait(false);
                }
                catch
                {
                    break;
                }

                foreach (var ip in ParseARecords(packet.Buffer))
                {
                    if (!hosts.Contains(ip)) hosts.Add(ip);
                }
            }
        }
        catch
        {
            // mDNS не обязателен: TCP-скан остаётся
        }
        return hosts;
    }

    private static byte[] BuildPtrQuery(string name)
    {
        using var ms = new System.IO.MemoryStream();
        ms.Write(new byte[] { 0x00, 0x00 });   // id
        ms.Write(new byte[] { 0x00, 0x00 });   // flags: запрос
        ms.Write(new byte[] { 0x00, 0x01 });   // qdcount
        ms.Write(new byte[] { 0x00, 0x00 });   // ancount
        ms.Write(new byte[] { 0x00, 0x00 });   // nscount
        ms.Write(new byte[] { 0x00, 0x00 });   // arcount

        foreach (var label in name.Split('.'))
        {
            var bytes = System.Text.Encoding.ASCII.GetBytes(label);
            ms.WriteByte((byte)bytes.Length);
            ms.Write(bytes);
        }
        ms.WriteByte(0);
        ms.Write(new byte[] { 0x00, 0x0C });   // PTR
        ms.Write(new byte[] { 0x00, 0x01 });   // IN
        return ms.ToArray();
    }

    /// <summary>A-записи из DNS-ответа: имя → IPv4 (минимальный парсер ресурсных записей).</summary>
    private static IEnumerable<string> ParseARecords(byte[] buffer)
    {
        var result = new List<string>();
        if (buffer.Length < 12) return result;

        var ancount = (buffer[6] << 8) | buffer[7];
        var offset = 12;

        // пропускаем вопросы
        var qdcount = (buffer[4] << 8) | buffer[5];
        for (var i = 0; i < qdcount && offset < buffer.Length; i++)
        {
            while (offset < buffer.Length && buffer[offset] != 0) offset += buffer[offset] + 1;
            offset += 5; // точка конца имени + type(2) + class(2)
        }

        for (var i = 0; i < ancount && offset + 10 < buffer.Length; i++)
        {
            // имя записи (сжатие не разворачиваем — нам важны только A)
            if (buffer[offset] == 0xC0) offset += 2;
            else
            {
                while (offset < buffer.Length && buffer[offset] != 0) offset += buffer[offset] + 1;
                offset += 1;
            }
            if (offset + 10 > buffer.Length) break;

            var type = (buffer[offset] << 8) | buffer[offset + 1];
            var rdlength = (buffer[offset + 8] << 8) | buffer[offset + 9];
            offset += 10;

            if (type == 1 && rdlength == 4 && offset + 4 <= buffer.Length)
            {
                result.Add($"{buffer[offset]}.{buffer[offset + 1]}.{buffer[offset + 2]}.{buffer[offset + 3]}");
            }
            offset += rdlength;
        }

        return result;
    }
}
