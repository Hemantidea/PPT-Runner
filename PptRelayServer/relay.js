const WebSocket = require('ws');
const http = require('http');

// ------------------------------------------------------------
// HTTP SERVER
// ------------------------------------------------------------

const server = http.createServer((req, res) => {
    res.writeHead(200, {
        'Content-Type': 'text/plain'
    });

    res.end('PPT Runner Cloud Relay is active.\n');
});

// ------------------------------------------------------------
// WEBSOCKET SERVER
// ------------------------------------------------------------

const wss = new WebSocket.Server({
    server
});

// ------------------------------------------------------------
// SESSION STORAGE
//
// {
//   "ABC123": {
//      desktop: WebSocket | null,
//      mobile: WebSocket | null
//   }
// }
// ------------------------------------------------------------

const sessions = new Map();


// ------------------------------------------------------------
// HELPERS
// ------------------------------------------------------------

function getSession(sessionId) {
    let session = sessions.get(sessionId);

    if (!session) {
        session = {
            desktop: null,
            mobile: null
        };

        sessions.set(sessionId, session);
    }

    return session;
}


function sendJson(socket, payload) {
    if (
        socket &&
        socket.readyState === WebSocket.OPEN
    ) {
        socket.send(JSON.stringify(payload));
    }
}


function notifyDesktop(session, payload) {
    sendJson(session.desktop, payload);
}


function cleanupSession(sessionId) {
    const session = sessions.get(sessionId);

    if (!session) {
        return;
    }

    if (
        !session.desktop &&
        !session.mobile
    ) {
        sessions.delete(sessionId);

        console.log(
            `🗑️ [${sessionId}] Session deleted.`
        );
    }
}


// ------------------------------------------------------------
// CONNECTION
// ------------------------------------------------------------

wss.on('connection', (ws, req) => {
    const url = new URL(
        req.url,
        `http://${req.headers.host}`
    );

    const parts = url.pathname
        .split('/')
        .filter(Boolean);

    // Expected:
    // /desktop/SESSION_ID
    // /mobile/SESSION_ID

    if (
        parts.length !== 2 ||
        (parts[0] !== 'desktop' &&
         parts[0] !== 'mobile')
    ) {
        ws.close(
            1008,
            'Invalid endpoint'
        );

        return;
    }

    const role = parts[0];
    const sessionId = parts[1];

    if (!sessionId) {
        ws.close(
            1008,
            'Missing session ID'
        );

        return;
    }

    const session =
        getSession(sessionId);

    // Used by the heartbeat system.
    ws.isAlive = true;

    ws.on('pong', () => {
        ws.isAlive = true;
    });

    ws.on('error', (error) => {
        console.error(
            `⚠️ [${sessionId}] ${role} WebSocket error:`,
            error.message
        );
    });

    // --------------------------------------------------------
    // DESKTOP
    // --------------------------------------------------------

    if (role === 'desktop') {

        // Replace an old desktop connection.
        if (
            session.desktop &&
            session.desktop !== ws &&
            session.desktop.readyState === WebSocket.OPEN
        ) {
            try {
                session.desktop.close(
                    1001,
                    'Replaced by new desktop connection'
                );
            } catch {
                // Ignore cleanup errors.
            }
        }

        session.desktop = ws;

        console.log(
            `🖥️ [${sessionId}] Desktop connected.`
        );

        // If mobile was already connected,
        // immediately tell this desktop.
        if (
            session.mobile &&
            session.mobile.readyState === WebSocket.OPEN
        ) {
            sendJson(ws, {
                type: 'MOBILE_CONNECTED'
            });
        }
    }


    // --------------------------------------------------------
    // MOBILE
    // --------------------------------------------------------

    if (role === 'mobile') {

        // Replace an old mobile connection.
        if (
            session.mobile &&
            session.mobile !== ws &&
            session.mobile.readyState === WebSocket.OPEN
        ) {
            try {
                session.mobile.close(
                    1001,
                    'Replaced by new mobile connection'
                );
            } catch {
                // Ignore cleanup errors.
            }
        }

        session.mobile = ws;

        console.log(
            `📱 [${sessionId}] Mobile connected.`
        );

        // Tell desktop immediately.
        notifyDesktop(session, {
            type: 'MOBILE_CONNECTED'
        });
    }


    // --------------------------------------------------------
    // MESSAGES
    // --------------------------------------------------------

    ws.on('message', (message) => {

        // Only the mobile client is allowed
        // to send presentation commands.
        if (role !== 'mobile') {
            return;
        }

        const desktop =
            session.desktop;

        if (
            desktop &&
            desktop.readyState === WebSocket.OPEN
        ) {
            // Forward exactly as received.
            desktop.send(message);
        }
    });


    // --------------------------------------------------------
    // CLOSE
    // --------------------------------------------------------

    ws.on('close', () => {

        // IMPORTANT:
        // Only clear the session role if THIS EXACT SOCKET
        // is still the active socket.
        //
        // This prevents an old connection's close event
        // from clearing a newly connected socket.

        if (
            role === 'desktop' &&
            session.desktop === ws
        ) {
            session.desktop = null;

            console.log(
                `🔴 [${sessionId}] Desktop disconnected.`
            );
        }

        if (
            role === 'mobile' &&
            session.mobile === ws
        ) {
            session.mobile = null;

            console.log(
                `🔴 [${sessionId}] Mobile disconnected.`
            );

            // Tell desktop that the mobile is gone.
            notifyDesktop(session, {
                type: 'MOBILE_DISCONNECTED'
            });
        }

        cleanupSession(sessionId);
    });
});


// ------------------------------------------------------------
// HEARTBEAT
//
// Ping every 1 second.
// If a socket failed to answer the previous ping,
// terminate it.
//
// This gives us fast dead-connection detection for MVP.
// We can tune the interval after real-world testing.
// ------------------------------------------------------------

const heartbeatInterval = setInterval(() => {

    wss.clients.forEach((ws) => {

        if (ws.isAlive === false) {

            console.log(
                '💀 Terminating dead WebSocket connection.'
            );

            ws.terminate();

            return;
        }

        ws.isAlive = false;

        try {
            ws.ping();
        } catch {
            ws.terminate();
        }
    });

}, 1000);


// ------------------------------------------------------------
// CLEANUP HEARTBEAT
// ------------------------------------------------------------

wss.on('close', () => {
    clearInterval(heartbeatInterval);
});


// ------------------------------------------------------------
// START SERVER
// ------------------------------------------------------------

const PORT =
    process.env.PORT || 3000;

server.listen(PORT, () => {
    console.log(
        `☁️ PPT Runner Relay Server running on port ${PORT}`
    );
});