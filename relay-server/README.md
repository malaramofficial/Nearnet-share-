# NetSetu Self-Hosted Relay Server

This tiny WebSocket relay pairs your **Host** phone and your **Friend** phone across mobile CGNAT without needing Tailscale or any third-party VPN app.

## Free 2-Minute Deployment (Render / Railway / VPS)

1. Upload the `relay-server/` folder to GitHub (or copy `server.js` + `package.json`).
2. Create a free Web Service on Render / Railway / Fly.io (or run `npm install && npm start` on any VPS).
   - **Build Command:** `npm install`
   - **Start Command:** `npm start`
3. Copy your deployed HTTPS/WSS URL (e.g. `wss://netsetu-relay.onrender.com`) and paste it into the **NetSetu** app's **Relay Server URL** field once!
