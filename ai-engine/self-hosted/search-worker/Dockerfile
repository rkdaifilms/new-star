FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN python -m pip install --upgrade pip && python -m pip install -r requirements.txt
COPY main.py .
RUN useradd --system --uid 10003 --create-home worker \
    && mkdir -p /data \
    && chown -R worker:worker /data /app
USER worker
EXPOSE 8093
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD curl -fsS http://127.0.0.1:8093/health || exit 1
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8093", "--workers", "1"]
