FROM python:3.12-slim
WORKDIR /app
COPY server.py worklog.html benchmark.html ./
ENV WORKLOG_FILE=/activity/worklog.jsonl
EXPOSE 8081
ENTRYPOINT ["python", "server.py", "--host", "0.0.0.0", "--service-url", "http://timesheet-service:8080", "--log-file", "/runtime/service.jsonl"]
