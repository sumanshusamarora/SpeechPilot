FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV HF_HOME=/workspace/.cache/huggingface

WORKDIR /workspace

COPY src/contracts/python /workspace/src/contracts/python
COPY src/backend /workspace/src/backend

RUN pip install --no-cache-dir -e /workspace/src/contracts/python -e /workspace/src/backend[dev]

WORKDIR /workspace/src/backend

EXPOSE 8000

CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload"]