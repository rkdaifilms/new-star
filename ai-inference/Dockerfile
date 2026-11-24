FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    CMAKE_ARGS="-DGGML_NATIVE=OFF" \
    MODEL_PATH=/models/model.gguf \
    N_THREADS=2 \
    N_CTX=4096

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential cmake pkg-config libopenblas-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && pip install --no-cache-dir -r requirements.txt

COPY app.py .
RUN useradd --system --uid 10001 appuser && mkdir -p /models && chown -R appuser:appuser /app /models
USER appuser

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
