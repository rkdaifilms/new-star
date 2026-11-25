FROM nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04
ENV DEBIAN_FRONTEND=noninteractive PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-pip python3-venv curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN python3 -m pip install --break-system-packages --upgrade pip && python3 -m pip install --break-system-packages torch==2.6.0 --index-url https://download.pytorch.org/whl/cu124 && python3 -m pip install --break-system-packages -r requirements.txt
COPY main.py .
RUN useradd --system --uid 10004 --create-home worker && mkdir -p /data /models/huggingface && chown -R worker:worker /data /models /app
USER worker
EXPOSE 8094
HEALTHCHECK --interval=30s --timeout=5s --start-period=300s --retries=3 CMD curl -fsS http://127.0.0.1:8094/health || exit 1
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8094", "--workers", "1"]
