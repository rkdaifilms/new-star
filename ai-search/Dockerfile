FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 NEW_STAR_SEARCH_DB=/var/lib/new-star-search/index.sqlite3
WORKDIR /app
COPY new_star_search.py .
RUN useradd --system --uid 10002 search && mkdir -p /var/lib/new-star-search && chown -R search:search /app /var/lib/new-star-search
USER search
EXPOSE 8081
CMD ["python","new_star_search.py","serve","--host","0.0.0.0","--port","8081"]
