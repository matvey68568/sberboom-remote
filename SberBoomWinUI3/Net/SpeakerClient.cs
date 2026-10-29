using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using SberBoom.WinUI.Protocol;

namespace SberBoom.WinUI.Net;

/// <summary>
/// WebSocket-клиент колонки: WSS :20000, самоподписанный сертификат,
/// бинарные кадры TLV. Порт shared/net/SpeakerClient.kt.
/// </summary>
public sealed class SpeakerClient : IAsyncDisposable
{
    private readonly string _host;
    private readonly int _port;
    private readonly string _clientName;
    private readonly string _clientId;

    private ClientWebSocket? _socket;
    private CancellationTokenSource? _loopCts;
    private Task? _receiveLoop;

    private readonly ConcurrentDictionary<string, TaskCompletionSource<byte[]>> _pending = new();
    private Func<Dictionary<int, object>, Task>? _pushHandler;

    public string? Token { get; private set; }
    public bool IsOpen => _socket?.State == WebSocketState.Open;

    public event Action<string>? Closed;

    public SpeakerClient(string host, int port, string? token, string clientName, string clientId)
    {
        _host = host;
        _port = port;
        Token = token;
        _clientName = clientName;
        _clientId = clientId;
    }

    public async Task ConnectAsync(int timeoutMs = 15000, CancellationToken ct = default)
    {
        var socket = new ClientWebSocket();
        // Колонка отдаёт самоподписанный сертификат — проверку цепочки выключаем.
        socket.Options.RemoteCertificateValidationCallback = (_, _, _, _) => true;
        socket.Options.SetRequestHeader("User-Agent", Proto.UserAgent);

        using var timeout = new CancellationTokenSource(timeoutMs);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct, timeout.Token);
        try
        {
            await socket.ConnectAsync(new Uri($"wss://{_host}:{_port}/"), linked.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw new SpeakerException($"колонка не ответила за {timeoutMs / 1000} с");
        }

        _socket = socket;
        _loopCts = new CancellationTokenSource();
        _receiveLoop = Task.Run(() => ReceiveLoopAsync(socket, _loopCts.Token));
    }

    private async Task ReceiveLoopAsync(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[64 * 1024];
        using var ms = new System.IO.MemoryStream();
        try
        {
            while (!ct.IsCancellationRequested && socket.State == WebSocketState.Open)
            {
                ms.SetLength(0);
                System.Net.WebSockets.ValueWebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(buffer.AsMemory(), ct).ConfigureAwait(false);
                    if (result.MessageType == WebSocketMessageType.Close) break;
                    ms.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Close) break;
                if (ms.Length == 0) continue;

                var bytes = ms.ToArray();
                await DispatchAsync(bytes).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // штатное закрытие
        }
        catch (Exception ex)
        {
            FailPending(ex);
            Closed?.Invoke(ex.Message);
            return;
        }
        finally
        {
            FailPending(new SpeakerException("соединение с колонкой закрыто"));
        }
        Closed?.Invoke("соединение закрыто");
    }

    private async Task DispatchAsync(byte[] bytes)
    {
        var msgId = Tlv.Text(Tlv.Decode(bytes), 2);
        if (!string.IsNullOrEmpty(msgId) && _pending.TryRemove(msgId!, out var tcs))
        {
            tcs.TrySetResult(bytes);
            return;
        }

        // Пуш без ожидающего запроса: сопряжение, обновления состояния.
        var body = EnvelopeReply.Parse(bytes).Body;
        if (body != null && _pushHandler != null)
        {
            await _pushHandler(body).ConfigureAwait(false);
        }
    }

    public void SetPushHandler(Func<Dictionary<int, object>, Task>? handler) => _pushHandler = handler;

    public async Task<byte[]> SendRawAsync(byte[] envelope, int timeoutMs = 6000)
    {
        var socket = _socket ?? throw new SpeakerException("нет соединения с колонкой");
        if (socket.State != WebSocketState.Open) throw new SpeakerException("соединение закрыто");

        var msgId = Requests.MsgIdOf(envelope);
        var tcs = new TaskCompletionSource<byte[]>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[msgId] = tcs;
        try
        {
            await socket.SendAsync(envelope.AsMemory(), WebSocketMessageType.Binary, true, CancellationToken.None)
                .ConfigureAwait(false);

            var completed = await Task.WhenAny(tcs.Task, Task.Delay(timeoutMs)).ConfigureAwait(false);
            if (completed != tcs.Task) throw new SpeakerException("колонка не ответила");

            var reply = await tcs.Task.ConfigureAwait(false);
            var status = Tlv.Num(Tlv.Decode(reply), Proto.EnvelopeFieldStatus);
            if (status == Proto.StatusUnauthorized) throw new AuthException("колонка не приняла токен");
            return reply;
        }
        finally
        {
            _pending.TryRemove(msgId, out _);
        }
    }

    /// <summary>Сырой ответ колонки (для repeated-полей: очередь, список BT).</summary>
    public async Task<byte[]> RequestRawAsync(byte[] requestData, int timeoutMs = 6000)
    {
        var envelope = Requests.RequestEnvelope(Requests.NewMsgId(), requestData, Token, _clientName, _clientId);
        return await SendRawAsync(envelope, timeoutMs).ConfigureAwait(false);
    }

    /// <summary>Запрос с телом и разбором ответа.</summary>
    public async Task<EnvelopeReply> RequestAsync(byte[] requestData, int timeoutMs = 6000)
    {
        var envelope = Requests.RequestEnvelope(Requests.NewMsgId(), requestData, Token, _clientName, _clientId);
        var raw = await SendRawAsync(envelope, timeoutMs).ConfigureAwait(false);
        return EnvelopeReply.Parse(raw);
    }

    /// <summary>Команда без ожидания ответа.</summary>
    public async Task FireAsync(byte[] requestData)
    {
        var socket = _socket ?? throw new SpeakerException("нет соединения с колонкой");
        var envelope = Requests.RequestEnvelope(Requests.NewMsgId(), requestData, Token, _clientName, _clientId);
        await socket.SendAsync(envelope.AsMemory(), WebSocketMessageType.Binary, true, CancellationToken.None)
            .ConfigureAwait(false);
    }

    /// <summary>ServerAction (DEEPLINK и прочее) — команда без ожидания ответа.</summary>
    public async Task FireServerActionAsync(string bodyJson)
    {
        var socket = _socket ?? throw new SpeakerException("нет соединения с колонкой");
        var envelope = Requests.ServerActionEnvelope(Requests.NewMsgId(), bodyJson, Token, _clientName, _clientId);
        await socket.SendAsync(envelope.AsMemory(), WebSocketMessageType.Binary, true, CancellationToken.None)
            .ConfigureAwait(false);
    }

    public async Task<SpeakerState?> GetStateAsync()
    {
        var reply = await RequestAsync(Requests.GetState).ConfigureAwait(false);
        return StateParser.Parse(ExtractJson(reply.Body, Proto.OpGetState));
    }

    /// <summary>Очередь воспроизведения (op=17) — сырой JSON.</summary>
    public async Task<string?> GetQueueJsonAsync()
    {
        var reply = await RequestAsync(Requests.GetQueue).ConfigureAwait(false);
        return ExtractJson(reply.Body, Proto.OpGetPlayingQueue);
    }

    /// <summary>
    /// JSON из ответа колонки. Форма зависит от модели: поле может прийти как
    /// строка или как вложенное сообщение `{op: {1: "json"}}`, поэтому ищем
    /// рекурсивно самую длинную строку, начинающуюся с '{'.
    /// </summary>
    public static string? ExtractJson(Dictionary<int, object>? body, int opTag)
    {
        if (body == null) return null;
        if (body.TryGetValue(opTag, out var direct) && direct is string s && s.TrimStart().StartsWith('{'))
            return s;
        return FindJson(body);
    }

    private static string? FindJson(object node)
    {
        switch (node)
        {
            case string s when s.TrimStart().StartsWith('{'):
                return s;
            case Dictionary<int, object> map:
            {
                string? best = null;
                foreach (var value in map.Values)
                {
                    var candidate = FindJson(value);
                    if (candidate != null && (best == null || candidate.Length > best.Length)) best = candidate;
                }
                return best;
            }
            default:
                return null;
        }
    }

    public async Task<int?> EchoAsync(int nonce, int timeoutMs = 4000)
    {
        var request = Tlv.Bytes(Proto.OpEcho, Tlv.Bytes(1, Tlv.Int(1, nonce)));
        var reply = await RequestAsync(request, timeoutMs).ConfigureAwait(false);
        var echo = reply.Body != null ? Tlv.Map(reply.Body, Proto.OpEcho) : null;
        var nested = echo != null ? Tlv.Map(echo, 2) : null;
        return nested != null ? Tlv.Num(nested, 1) : null;
    }

    /// <summary>PIN-сопряжение: push op=4 (WAITING), затем op=6 (OK + токен) после нажатия «+».</summary>
    public async Task<string> PairAsync(Action<string?>? onWaiting = null, int timeoutMs = Proto.PairTimeoutMs)
    {
        var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);

        SetPushHandler(body =>
        {
            if (Tlv.Map(body, Proto.OpPinConnect) is { } pin)
            {
                switch (Tlv.Num(pin, 1))
                {
                    case Proto.PairAuthorized:
                        if (Tlv.Text(pin, 2) is { Length: >= 16 } tok) tcs.TrySetResult(tok);
                        break;
                    case Proto.PairWaiting:
                        onWaiting?.Invoke(Tlv.Text(pin, 2));
                        break;
                    case Proto.PairBusy:
                        tcs.TrySetException(new PairException("сессия сопряжения уже активна"));
                        break;
                    case Proto.PairDisabled:
                        tcs.TrySetException(new PairException("сопряжение выключено на колонке"));
                        break;
                }
            }

            if (Tlv.Map(body, 6) is { } confirm)
            {
                switch (Tlv.Num(confirm, 1))
                {
                    case Proto.PairConfirmOk:
                        if (Tlv.Text(confirm, 2) is { Length: >= 16 } tok) tcs.TrySetResult(tok);
                        break;
                    case Proto.PairConfirmRejected:
                        tcs.TrySetException(new PairException("колонка отклонила сопряжение"));
                        break;
                }
            }
            return Task.CompletedTask;
        });

        try
        {
            await FireAsync(Requests.PairInit).ConfigureAwait(false);

            var done = await Task.WhenAny(tcs.Task, Task.Delay(timeoutMs)).ConfigureAwait(false);
            if (done != tcs.Task) throw new SpeakerException("нажатие «+» на колонке не получено");

            Token = await tcs.Task.ConfigureAwait(false);
            return Token!;
        }
        finally
        {
            SetPushHandler(null);
        }
    }

    private void FailPending(Exception ex)
    {
        foreach (var key in _pending.Keys)
        {
            if (_pending.TryRemove(key, out var tcs)) tcs.TrySetException(ex);
        }
    }

    public async ValueTask DisposeAsync()
    {
        _loopCts?.Cancel();
        if (_socket is { State: WebSocketState.Open })
        {
            try
            {
                await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None)
                    .ConfigureAwait(false);
            }
            catch
            {
                // закрываем как есть
            }
        }
        _socket?.Dispose();
        _socket = null;
        if (_receiveLoop != null)
        {
            try { await _receiveLoop.ConfigureAwait(false); } catch { /* игнорируем */ }
        }
    }
}
