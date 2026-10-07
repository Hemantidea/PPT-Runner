using System;
using System.Threading;
using System.Threading.Tasks;
using Fleck;

namespace PptRunner.Desktop.Networking;

public sealed class LocalWebSocketServer : IDisposable
{
    private const int Port = 8082;

    private static readonly TimeSpan HeartbeatInterval =
        TimeSpan.FromSeconds(1);

    private static readonly TimeSpan HeartbeatTimeout =
        TimeSpan.FromSeconds(2.5);

    private readonly object _lock = new();

    private WebSocketServer? _server;
    private IWebSocketConnection? _activeSocket;

    private CancellationTokenSource? _heartbeatCts;
    private Task? _heartbeatTask;

    private bool _isRunning;

    public event Action? ClientConnected;
    public event Action? ClientDisconnected;
    public event Action<string>? MessageReceived;
    public event Action<Exception>? ServerError;

    public bool IsRunning => _isRunning;

    public void Start()
    {
        if (_isRunning)
            return;

        try
        {
            FleckLog.Level = LogLevel.Warn;

            _server = new WebSocketServer(
                $"ws://0.0.0.0:{Port}");

            _server.Start(socket =>
            {
                socket.OnOpen = () =>
                {
                    HandleSocketOpened(socket);
                };

                socket.OnMessage = message =>
                {
                    HandleSocketMessage(socket, message);
                };

                socket.OnPong = _ =>
                {
                    HandleSocketPong(socket);
                };

                socket.OnClose = () =>
                {
                    HandleSocketClosed(socket);
                };

                socket.OnError = exception =>
                {
                    ServerError?.Invoke(exception);
                };
            });

            _isRunning = true;

            _heartbeatCts =
                new CancellationTokenSource();

            _heartbeatTask =
                HeartbeatLoopAsync(
                    _heartbeatCts.Token);
        }
        catch (Exception ex)
        {
            _server = null;
            _isRunning = false;

            ServerError?.Invoke(ex);
            throw;
        }
    }

    private void HandleSocketOpened(
        IWebSocketConnection socket)
    {
        lock (_lock)
        {
            // MVP allows one controller at a time.
            if (_activeSocket is not null &&
                !ReferenceEquals(
                    _activeSocket,
                    socket) &&
                _activeSocket.IsAvailable)
            {
                try
                {
                    _activeSocket.Close(1001);
                }
                catch
                {
                    // Ignore old-client shutdown errors.
                }
            }

            _activeSocket = socket;
        }

        ClientConnected?.Invoke();
    }

    private void HandleSocketMessage(
        IWebSocketConnection socket,
        string message)
    {
        bool isActive;

        lock (_lock)
        {
            isActive =
                ReferenceEquals(
                    _activeSocket,
                    socket);
        }

        if (!isActive)
            return;

        MessageReceived?.Invoke(message);
    }

    private void HandleSocketPong(
        IWebSocketConnection socket)
    {
        lock (_lock)
        {
            if (ReferenceEquals(
                    _activeSocket,
                    socket))
            {
                _lastPongUtc = DateTime.UtcNow;
            }
        }
    }

    private void HandleSocketClosed(
        IWebSocketConnection socket)
    {
        bool wasActive = false;

        lock (_lock)
        {
            if (ReferenceEquals(
                    _activeSocket,
                    socket))
            {
                _activeSocket = null;
                wasActive = true;
            }
        }

        if (wasActive)
        {
            ClientDisconnected?.Invoke();
        }
    }

    private DateTime _lastPongUtc =
        DateTime.UtcNow;

    private async Task HeartbeatLoopAsync(
        CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(
                    HeartbeatInterval,
                    cancellationToken);

                IWebSocketConnection? socket;

                lock (_lock)
                {
                    socket = _activeSocket;
                }

                if (socket is null ||
                    !socket.IsAvailable)
                {
                    continue;
                }

                var elapsed =
                    DateTime.UtcNow - _lastPongUtc;

                if (elapsed > HeartbeatTimeout)
                {
                    try
                    {
                        socket.Close(1001);
                    }
                    catch
                    {
                        // OnClose will clean up state.
                    }

                    continue;
                }

                try
                {
                    await socket.SendPing(
                        Array.Empty<byte>());
                }
                catch (Exception ex)
                {
                    ServerError?.Invoke(ex);

                    try
                    {
                        socket.Close(1001);
                    }
                    catch
                    {
                        // Ignore cleanup errors.
                    }
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                ServerError?.Invoke(ex);
            }
        }
    }

    public void Stop()
    {
        if (!_isRunning)
            return;

        try
        {
            _heartbeatCts?.Cancel();

            IWebSocketConnection? socket;

            lock (_lock)
            {
                socket = _activeSocket;
                _activeSocket = null;
            }

            try
            {
                socket?.Close(1001);
            }
            catch
            {
                // Ignore shutdown errors.
            }

            _server?.Dispose();
        }
        catch (Exception ex)
        {
            ServerError?.Invoke(ex);
        }
        finally
        {
            _server = null;
            _isRunning = false;

            _heartbeatCts?.Dispose();
            _heartbeatCts = null;
            _heartbeatTask = null;
        }
    }

    public void Dispose()
    {
        Stop();
    }
}