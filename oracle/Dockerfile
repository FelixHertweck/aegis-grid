FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ ./src/

ENV ORACLE_HOST=0.0.0.0 \
    ORACLE_PORT=8080 \
    ORACLE_LOG_DIR=/var/log/oracle \
    ORACLE_CONTENT_DIR=/app/content

EXPOSE 8080

ENTRYPOINT ["python", "-m", "src.main"]
