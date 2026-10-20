FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && useradd --uid 10001 --create-home scoreteam \
    && mkdir /data && chown scoreteam:scoreteam /data
COPY security.py service.py catalog.py publishing.py backup_archive.py app_release.py annotations.py programs.py program_freezes.py operation_records.py personal_folders.py score_numbers.py number_inventory.py number_release.py expanded_candidate.py expanded_protocol.py expanded_permanent.py permanent_storage.py permanent_backup.py permanent_deletion.py permanent_journal.py permanent_events.py permanent_runtime.py multiteam.py multiteam_storage.py multiteam_cli.py team_jobs.py server.py ./
USER 10001:10001
VOLUME /data
ENTRYPOINT ["python", "/app/server.py", "--data", "/data"]
