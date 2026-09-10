# LogStream React Dashboard — Backend Connected

This frontend is prepared for the Log Stream Spring Boot backend.

## Run

```cmd
npm install
npm run dev
```

Open:

http://localhost:5173

## Backend expected

REST:
- http://localhost:8080/api/logs/count
- http://localhost:8080/api/logs/stats
- http://localhost:8080/api/logs?limit=100
- http://localhost:8080/api/logs/search?q=error&level=ERROR&service=payment-service&limit=100

WebSocket:
- ws://localhost:8080/ws/logs

gRPC ingestion remains:
- localhost:9090

The first two REST endpoints already exist in the current Log Stream backend. The list/search REST endpoints and WebSocket endpoint must be added to the backend for the Search Logs and Live Tail screens.

## Important

Do not open port 9090 in a browser. gRPC clients such as Postman should connect to `localhost:9090`.
