using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace SberBoom.WinUI.Net;

public sealed class ZvukItem
{
    public string Id { get; init; } = "";
    public string Type { get; init; } = "";
    public string Title { get; init; } = "";
    public string Subtitle { get; set; } = "";
    public string? CoverUrl { get; set; }

    public string Line => string.IsNullOrWhiteSpace(Subtitle) ? Title : Title + " — " + Subtitle;

    // В ListView элемент показывается через ToString, а onClick отдаёт сам объект —
    // так не приходится искать его по тексту (одинаковые названия ломали выбор).
    public override string ToString() => Line;
}

/// <summary>
/// Каталог Звука: анонимный токен, поиск (tiny API) и метаданные треков (GraphQL).
/// Порт net/ZvukApi.kt в объёме, нужном пульту: поиск, названия для очереди,
/// ссылки staros:// для воспроизведения на колонке.
/// </summary>
public sealed class ZvukApi
{
    private const string ProfileUrl = "https://zvuk.com/api/tiny/profile";
    private const string SearchUrl = "https://zvuk.com/api/tiny/search";
    private const string GraphQlUrl = "https://zvuk.com/api/v1/graphql";
    private const string CoverSize = "600x600";

    private const string GqlTracks =
        "query getTracks($ids:[ID!]!){getTracks(ids:$ids){id title duration explicit artists{id title} release{id title image{src}}}}";

    private static readonly Dictionary<string, string> IdParam = new()
    {
        ["track"] = "tid",
        ["podcast"] = "tid",
        ["abook"] = "tid",
        ["artist"] = "pid",
        ["release"] = "pid",
        ["playlist"] = "pid",
    };

    private static readonly HashSet<string> ValidPt = new() { "track", "podcast", "artist", "release", "playlist" };

    private readonly HttpClient _http;
    private string? _token;

    public ZvukApi()
    {
        var handler = new HttpClientHandler
        {
            UseCookies = true,
            CookieContainer = new CookieContainer(),
            AllowAutoRedirect = true,   // профиль отдаёт 307 на зеркало
        };
        _http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(15) };
        _http.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", "Mozilla/5.0 SberBoomRemote");
    }

    private async Task<string> EnsureTokenAsync(bool force = false)
    {
        if (!force && _token != null) return _token;

        using var response = await _http.GetAsync(ProfileUrl).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);

        using var doc = JsonDocument.Parse(body);
        if (!doc.RootElement.TryGetProperty("result", out var result) ||
            !result.TryGetProperty("token", out var token) ||
            token.ValueKind != JsonValueKind.String)
        {
            throw new InvalidOperationException("Звук: токен не получен");
        }

        _token = token.GetString()!;
        return _token;
    }

    /// <summary>Поиск по каталогу: исполнители, релизы, треки, плейлисты.</summary>
    public async Task<List<ZvukItem>> SearchAsync(string query, int limit = 10)
    {
        var items = new List<ZvukItem>();
        if (string.IsNullOrWhiteSpace(query)) return items;

        var url = $"{SearchUrl}?query={Uri.EscapeDataString(query)}&type=artist,release,track,playlist&limit={limit}";
        var token = await EnsureTokenAsync().ConfigureAwait(false);

        var body = await GetWithTokenAsync(url, token).ConfigureAwait(false);
        if (body == null)
        {
            token = await EnsureTokenAsync(force: true).ConfigureAwait(false);
            body = await GetWithTokenAsync(url, token).ConfigureAwait(false);
        }
        if (body == null) return items;

        using var doc = JsonDocument.Parse(body);
        if (!doc.RootElement.TryGetProperty("result", out var result) ||
            !result.TryGetProperty("search", out var search)) return items;

        foreach (var (section, type) in new[]
                 {
                     ("artists", "artist"),
                     ("releases", "release"),
                     ("tracks", "track"),
                     ("playlists", "playlist"),
                 })
        {
            if (!search.TryGetProperty(section, out var node) || !node.TryGetProperty("items", out var arr)) continue;
            foreach (var item in arr.EnumerateArray().Take(limit))
            {
                var parsed = ToItem(item, type);
                if (parsed != null) items.Add(parsed);
            }
        }

        await EnrichCoversAsync(items).ConfigureAwait(false);
        return items;
    }

    private async Task<string?> GetWithTokenAsync(string url, string token)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("X-Auth-Token", token);
        using var response = await _http.SendAsync(request).ConfigureAwait(false);
        if (response.StatusCode == HttpStatusCode.Unauthorized) return null;
        if (!response.IsSuccessStatusCode) return null;
        return await response.Content.ReadAsStringAsync().ConfigureAwait(false);
    }

    private static ZvukItem? ToItem(JsonElement item, string type)
    {
        if (item.ValueKind != JsonValueKind.Object) return null;
        var id = item.TryGetProperty("id", out var idNode)
            ? (idNode.ValueKind == JsonValueKind.Number ? idNode.GetRawText() : idNode.GetString())
            : null;
        if (string.IsNullOrEmpty(id)) return null;

        var title = item.TryGetProperty("title", out var t) ? t.GetString() ?? "" : "";

        var subtitle = type switch
        {
            "artist" => "исполнитель",
            "release" => "альбом",
            "playlist" => "плейлист",
            "track" => JoinArtists(item),
            _ => "",
        };

        return new ZvukItem { Id = id!, Type = type, Title = title, Subtitle = subtitle };
    }

    private static string JoinArtists(JsonElement item)
    {
        if (!item.TryGetProperty("artists", out var artists) || artists.ValueKind != JsonValueKind.Array)
            return "";
        var names = artists.EnumerateArray()
            .Select(a => a.TryGetProperty("title", out var t) ? t.GetString() : null)
            .Where(s => !string.IsNullOrWhiteSpace(s));
        return string.Join(", ", names!);
    }

    private async Task EnrichCoversAsync(List<ZvukItem> items)
    {
        var ids = items.Select(i => i.Id).Distinct().ToList();
        if (ids.Count == 0) return;

        try
        {
            var token = await EnsureTokenAsync().ConfigureAwait(false);
            var payload = JsonSerializer.Serialize(new
            {
                query = GqlTracks,
                variables = new { ids },
            });

            using var request = new HttpRequestMessage(HttpMethod.Post, GraphQlUrl)
            {
                Content = new StringContent(payload, Encoding.UTF8, "application/json"),
            };
            request.Headers.TryAddWithoutValidation("X-Auth-Token", token);

            using var response = await _http.SendAsync(request).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode) return;
            var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);

            using var doc = JsonDocument.Parse(body);
            if (!doc.RootElement.TryGetProperty("data", out var data) ||
                !data.TryGetProperty("getTracks", out var tracks) ||
                tracks.ValueKind != JsonValueKind.Array) return;

            foreach (var track in tracks.EnumerateArray())
            {
                if (!track.TryGetProperty("id", out var idNode)) continue;
                var id = idNode.ValueKind == JsonValueKind.Number ? idNode.GetRawText() : idNode.GetString();
                if (id == null) continue;

                var item = items.FirstOrDefault(i => i.Id == id);
                if (item == null) continue;

                if (item.CoverUrl == null &&
                    track.TryGetProperty("release", out var release) &&
                    release.TryGetProperty("image", out var image) &&
                    image.TryGetProperty("src", out var src) &&
                    src.ValueKind == JsonValueKind.String)
                {
                    item.CoverUrl = src.GetString()!.Replace("{size}", CoverSize);
                }

                if (string.IsNullOrWhiteSpace(item.Subtitle))
                {
                    var artists = JoinArtists(track);
                    if (!string.IsNullOrWhiteSpace(artists)) item.Subtitle = artists;
                }
            }
        }
        catch
        {
            // обложки и подписи — украшение: без них список остаётся рабочим
        }
    }

    /// <summary>Названия треков по id (для очереди и истории).</summary>
    public async Task<Dictionary<string, ZvukItem>> TracksAsync(IEnumerable<string> ids)
    {
        var list = ids.Where(s => !string.IsNullOrWhiteSpace(s)).Distinct().ToList();
        var result = new Dictionary<string, ZvukItem>();
        if (list.Count == 0) return result;

        var items = list.Select(id => new ZvukItem { Id = id, Type = "track", Title = "" }).ToList();
        await EnrichCoversAsync(items).ConfigureAwait(false);
        foreach (var item in items) result[item.Id] = item;
        return result;
    }

    /// <summary>staros://-ссылка для command на колонке.</summary>
    public static string? Deeplink(string? pt, string? id)
    {
        var type = (pt ?? "").ToLowerInvariant();
        var clean = (id ?? "").Trim();
        if (!ValidPt.Contains(type) || clean.Length == 0 || !clean.All(char.IsDigit)) return null;
        if (!IdParam.TryGetValue(type, out var param)) return null;
        return $"staros://music?{param}={clean}&pt={type}";
    }
}
