FROM python:3.12-slim
WORKDIR /app
COPY server.py ./
COPY admin ./admin
ENV HOST=0.0.0.0 PYTHONUNBUFFERED=1
CMD ["python3", "server.py"]
