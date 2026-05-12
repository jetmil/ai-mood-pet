FROM python:3.12-slim

# ffmpeg для TTS post-process
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY server_app.py ./
COPY characters/ ./characters/

ENV PYTHONUNBUFFERED=1
EXPOSE 8350

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
    CMD curl -sf http://localhost:8350/health || exit 1

CMD ["uvicorn", "server_app:app", "--host", "0.0.0.0", "--port", "8350"]
