FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 NEW_STAR_SEARCH_DB=/var/lib/new-star-search/index.sqlite3 HF_HOME=/var/cache/huggingface
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY new_star_search.py vector_backend.py vector_server.py vector_sync.py ./
RUN useradd --system --uid 10002 search && mkdir -p /var/lib/new-star-search /var/cache/huggingface && chown -R search:search /app /var/lib/new-star-search /var/cache/huggingface
USER search
EXPOSE 8081 8082
CMD ["python","new_star_search.py","serve","--host","0.0.0.0","--port","8081"]
