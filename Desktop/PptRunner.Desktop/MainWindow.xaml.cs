using System;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;

using PptRunner.Desktop.Core;
using PptRunner.Desktop.Input;
using PptRunner.Desktop.Networking;
using PptRunner.Desktop.QR;

namespace PptRunner.Desktop;

public partial class MainWindow : Window
{
    private const string RelayBaseUrl =
        "wss://ppt-runner-relay.onrender.com";

    private readonly string _sessionId;
    private readonly string _localIp;

    private readonly LocalWebSocketServer _localServer;
    private readonly KeyboardInputController _inputController;
    private readonly CommandGateway _commandGateway;
    private readonly RelayClient _relayClient;
    private readonly TransportStateManager _transportStateManager;

    public MainWindow()
    {
        InitializeComponent();

        _sessionId = GenerateSessionId();
        _localIp = GetLocalIPAddress();

        _inputController =
            new KeyboardInputController();

        _commandGateway =
            new CommandGateway(_inputController);

        _localServer =
            new LocalWebSocketServer();

        _relayClient =
            new RelayClient(RelayBaseUrl);

        _transportStateManager =
            new TransportStateManager();

        InitializeSession();
        WireEvents();

        Loaded += MainWindow_Loaded;
        Closed += MainWindow_Closed;
    }

    // ------------------------------------------------------------
    // Session / QR
    // ------------------------------------------------------------

    private void InitializeSession()
    {
        string localWsUrl =
            $"ws://{_localIp}:8082";

        var qrPayload = new
        {
            v = 1,
            sid = _sessionId,
            local = localWsUrl,
            relay = RelayBaseUrl
        };

        string qrJson =
            JsonSerializer.Serialize(qrPayload);

        SessionIdText.Text =
            _sessionId;

        ConnectedSessionText.Text =
            _sessionId;

        QrImage.Source =
            QrCodeService.Generate(qrJson);

        SetWaitingState();
    }

    // ------------------------------------------------------------
    // Connected UI
    // ------------------------------------------------------------

    private void ShowConnectedState(
        string transport)
    {
        StatusText.Text = "Connected";

        StatusDot.Fill =
            new SolidColorBrush(
                Color.FromRgb(8, 127, 140));

        ConnectView.Visibility =
            Visibility.Collapsed;

        ConnectedView.Visibility =
            Visibility.Visible;

        ConnectedSessionText.Text =
            _sessionId;

        // Use FindName so we don't depend on a stale
        // generated XAML field.
        if (FindName("TransportStatusText")
            is System.Windows.Controls.TextBlock transportText)
        {
            transportText.Text = transport;
        }
    }

    // ------------------------------------------------------------
    // Event wiring
    // ------------------------------------------------------------

    private void WireEvents()
    {
        // Local transport
        _localServer.ClientConnected +=
            OnClientConnected;

        _localServer.ClientDisconnected +=
            OnClientDisconnected;

        _localServer.MessageReceived +=
            OnMessageReceived;

        _localServer.ServerError +=
            OnServerError;

        // Relay transport
        _relayClient.Connected +=
            OnRelayConnected;

        _relayClient.Disconnected +=
            OnRelayDisconnected;

        _relayClient.MobileConnected +=
            OnRelayMobileConnected;

        _relayClient.MobileDisconnected +=
            OnRelayMobileDisconnected;

        _relayClient.MessageReceived +=
            OnRelayMessageReceived;

        _relayClient.Error +=
            OnRelayError;

        // Command gateway
        _commandGateway.CommandExecuted +=
            OnCommandExecuted;

        _commandGateway.CommandIgnored +=
            OnCommandIgnored;

        _commandGateway.InvalidMessage +=
            OnInvalidMessage;

        // Transport state
        _transportStateManager.StateChanged +=
            OnTransportStateChanged;
    }

    // ------------------------------------------------------------
    // Window lifecycle
    // ------------------------------------------------------------

    private void MainWindow_Loaded(
        object sender,
        RoutedEventArgs e)
    {
        try
        {
            // Start local WebSocket server.
            _localServer.Start();

            // Start relay connection in standby.
            _relayClient.Start(_sessionId);

            SetWaitingState();
        }
        catch (Exception ex)
        {
            StatusText.Text =
                "Connection unavailable";

            StatusDot.Fill =
                new SolidColorBrush(
                    Color.FromRgb(180, 80, 80));

            QrStatusText.Text =
                "Could not start local server";

            System.Diagnostics.Debug.WriteLine(
                $"Startup failed: {ex}");
        }
    }

    private async void MainWindow_Closed(
        object? sender,
        EventArgs e)
    {
        _localServer.Stop();

        await _relayClient.StopAsync();
    }

    // ------------------------------------------------------------
    // LOCAL TRANSPORT
    // ------------------------------------------------------------

    private void OnClientConnected()
    {
        _transportStateManager
            .SetLocalConnected(true);
    }

    private void OnClientDisconnected()
    {
        _transportStateManager
            .SetLocalConnected(false);
    }

    private void OnMessageReceived(
        string message)
    {
        // Local command -> common gateway.
        _commandGateway.HandleMessage(message);
    }

    // ------------------------------------------------------------
    // RELAY TRANSPORT
    // ------------------------------------------------------------

    private void OnRelayConnected()
    {
        System.Diagnostics.Debug.WriteLine(
            "[Relay] Desktop connected to relay.");
    }

    private void OnRelayDisconnected()
    {
        System.Diagnostics.Debug.WriteLine(
            "[Relay] Desktop disconnected from relay.");
    }

    private void OnRelayMobileConnected()
    {
        _transportStateManager
            .SetRelayMobileConnected(true);
    }

    private void OnRelayMobileDisconnected()
    {
        _transportStateManager
            .SetRelayMobileConnected(false);
    }

    private void OnRelayMessageReceived(
        string message)
    {
        // Relay command -> same common gateway.
        _commandGateway.HandleMessage(message);
    }

    private void OnRelayError(
        Exception exception)
    {
        System.Diagnostics.Debug.WriteLine(
            $"[Relay] {exception.Message}");
    }

    // ------------------------------------------------------------
    // COMMAND EVENTS
    // ------------------------------------------------------------

    private void OnCommandExecuted(
        string command,
        int sequence)
    {
        // Do not change connection state here.
        //
        // Connection state is owned by
        // TransportStateManager.
    }

    private void OnCommandIgnored(
        string message)
    {
        System.Diagnostics.Debug.WriteLine(
            $"Command ignored: {message}");
    }

    private void OnInvalidMessage(
        string message)
    {
        System.Diagnostics.Debug.WriteLine(
            $"Invalid command: {message}");
    }

    // ------------------------------------------------------------
    // TRANSPORT STATE
    // ------------------------------------------------------------

    private void OnTransportStateChanged(
        TransportState state)
    {
        Dispatcher.BeginInvoke(() =>
        {
            switch (state)
            {
                case TransportState.Local:

                    ShowConnectedState(
                        "Connected locally");

                    break;

                case TransportState.Relay:

                    ShowConnectedState(
                        "Connected via relay");

                    break;

                case TransportState.Waiting:

                    SetWaitingState();

                    break;
            }
        });
    }

    // ------------------------------------------------------------
    // SERVER ERRORS
    // ------------------------------------------------------------

    private void OnServerError(
        Exception exception)
    {
        Dispatcher.BeginInvoke(() =>
        {
            StatusText.Text =
                "Connection unavailable";

            StatusDot.Fill =
                new SolidColorBrush(
                    Color.FromRgb(180, 80, 80));

            System.Diagnostics.Debug.WriteLine(
                $"Local WebSocket error: {exception}");
        });
    }

    // ------------------------------------------------------------
    // UI STATE
    // ------------------------------------------------------------

    private void SetWaitingState()
    {
        StatusText.Text =
            "Ready to connect";

        StatusDot.Fill =
            new SolidColorBrush(
                Color.FromRgb(140, 140, 140));

        ConnectView.Visibility =
            Visibility.Visible;

        ConnectedView.Visibility =
            Visibility.Collapsed;

        QrStatusText.Text =
            "Waiting for phone…";
    }

    // ------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------

    private static string GenerateSessionId()
    {
        return Guid.NewGuid()
            .ToString("N")
            .Substring(0, 6)
            .ToUpperInvariant();
    }

    private static string GetLocalIPAddress()
    {
        try
        {
            var activeAddress =
                NetworkInterface
                    .GetAllNetworkInterfaces()
                    .Where(nic =>
                        nic.OperationalStatus ==
                        OperationalStatus.Up &&
                        nic.NetworkInterfaceType !=
                        NetworkInterfaceType.Loopback)
                    .SelectMany(nic =>
                        nic.GetIPProperties()
                            .UnicastAddresses)
                    .Select(address =>
                        address.Address)
                    .FirstOrDefault(address =>
                        address.AddressFamily ==
                        AddressFamily.InterNetwork);

            return activeAddress?.ToString()
                   ?? "127.0.0.1";
        }
        catch
        {
            return "127.0.0.1";
        }
    }
}