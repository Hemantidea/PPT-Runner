using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace PptRunner.Desktop.Networking;

public sealed class RelayClient : IDisposable
{
    private static readonly TimeSpan KeepAliveInterval =
        TimeSpan.FromSeconds(1);

    private static readonly TimeSpan KeepAliveTimeout =
        TimeSpan.FromSeconds(2);

    private readonly string _relayBaseUrl;

    private ClientWebSocket? _client;
    private CancellationTokenSource? _cts;
    private Task? _connectionTask;

    private bool _mobileConnected;
    private bool _disposed;

    public event Action? Connected;
    public event Action? Disconnected;

    public event Action? MobileConnected;
    public event Action? MobileDisconnected;

    public event Action<string>? MessageReceived;
    public event Action<Exception>? Error;

    public bool IsConnected =>
        _client?.State == WebSocketState.Open;

    public bool IsMobileConnected =>
        _mobileConnected;

    public RelayClient(string relayBaseUrl)
    {
        _relayBaseUrl =
            relayBaseUrl.TrimEnd('/');
    }

    public void Start(string sessionId)
    {
        if (_disposed)
            throw new ObjectDisposedException(
                nameof(RelayClient));

        if (_connectionTask is not null)
            return;

        _cts =
            new CancellationTokenSource();

        _connectionTask =
            RunAsync(
                sessionId,
                _cts.Token);
    }

    private async Task RunAsync(
        string sessionId,
        CancellationToken cancellationToken)
    {
        string relayUrl =
            $"{_relayBaseUrl}/desktop/{sessionId}";

        while (!cancellationToken.IsCancellationRequested)
        {
            ClientWebSocket? socket = null;

            try
            {
                socket = new ClientWebSocket();

                // .NET 9+ / .NET 10 Ping/Pong keep-alive.
                socket.Options.KeepAliveInterval =
                    KeepAliveInterval;

                socket.Options.KeepAliveTimeout =
                    KeepAliveTimeout;

                _client = socket;

                await socket.ConnectAsync(
                    new Uri(relayUrl),
                    cancellationToken);

                Connected?.Invoke();

                await ReceiveLoopAsync(
                    socket,
                    cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                Error?.Invoke(ex);
            }
            finally
            {
                if (_mobileConnected)
                {
                    _mobileConnected = false;
                    MobileDisconnected?.Invoke();
                }

                if (ReferenceEquals(
                        _client,
                        socket))
                {
                    _client = null;
                }

                socket?.Dispose();

                Disconnected?.Invoke();
            }

            if (cancellationToken.IsCancellationRequested)
                break;

            try
            {
                await Task.Delay(
                    TimeSpan.FromSeconds(1),
                    cancellationToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task ReceiveLoopAsync(
        ClientWebSocket socket,
        CancellationToken cancellationToken)
    {
        byte[] buffer = new byte[4096];

        while (
            socket.State == WebSocketState.Open &&
            !cancellationToken.IsCancellationRequested)
        {
            using var messageStream =
                new MemoryStream();

            WebSocketReceiveResult result;

            do
            {
                result =
                    await socket.ReceiveAsync(
                        new ArraySegment<byte>(
                            buffer),
                        cancellationToken);

                if (result.MessageType ==
                    WebSocketMessageType.Close)
                {
                    return;
                }

                if (result.Count > 0)
                {
                    messageStream.Write(
                        buffer,
                        0,
                        result.Count);
                }
            }
            while (!result.EndOfMessage);

            if (messageStream.Length == 0)
                continue;

            string message =
                Encoding.UTF8.GetString(
                    messageStream.ToArray());

            if (HandleRelayControlMessage(message))
                continue;

            MessageReceived?.Invoke(message);
        }
    }

    private bool HandleRelayControlMessage(
        string message)
    {
        try
        {
            using var document =
                JsonDocument.Parse(message);

            if (!document.RootElement
                .TryGetProperty(
                    "type",
                    out var typeElement))
            {
                return false;
            }

            string? type =
                typeElement.GetString();

            switch (type)
            {
                case "MOBILE_CONNECTED":

                    if (!_mobileConnected)
                    {
                        _mobileConnected = true;
                        MobileConnected?.Invoke();
                    }

                    return true;

                case "MOBILE_DISCONNECTED":

                    if (_mobileConnected)
                    {
                        _mobileConnected = false;
                        MobileDisconnected?.Invoke();
                    }

                    return true;

                default:
                    return false;
            }
        }
        catch (JsonException)
        {
            return false;
        }
    }

    public async Task StopAsync()
    {
        _cts?.Cancel();

        if (_client is
            { State: WebSocketState.Open })
        {
            try
            {
                await _client.CloseAsync(
                    WebSocketCloseStatus.NormalClosure,
                    "Desktop shutting down",
                    CancellationToken.None);
            }
            catch
            {
                // Ignore shutdown errors.
            }
        }

        if (_connectionTask is not null)
        {
            try
            {
                await _connectionTask;
            }
            catch
            {
                // Connection loop handles its own errors.
            }
        }

        _connectionTask = null;
        _client = null;

        _cts?.Dispose();
        _cts = null;
    }

    public void Dispose()
    {
        if (_disposed)
            return;

        _disposed = true;

        _cts?.Cancel();
        _client?.Dispose();
        _cts?.Dispose();

        _client = null;
        _cts = null;
        _connectionTask = null;
    }
}