FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONPATH=/app/ai-engine
WORKDIR /app
COPY ai-engine ./ai-engine
RUN useradd --system --uid 10001 newstar && mkdir -p /var/lib/new-star && chown -R newstar:newstar /app /var/lib/new-star
USER newstar
EXPOSE 8090
CMD ["python","-m","ai_engine","--models-file","/config/models.json","serve","--host","0.0.0.0","--port","8090"]
