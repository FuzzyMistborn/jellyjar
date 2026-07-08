FROM python:3.14-slim

# Enable non-free for intel-media-va-driver
RUN sed -i 's/^Components: main$/Components: main contrib non-free non-free-firmware/' \
        /etc/apt/sources.list.d/debian.sources

# Install ffmpeg + Intel iHD VA-API driver (required for QSV and VAAPI on gen8+ iGPUs)
# NVENC needs no extra packages; vainfo is for runtime diagnostics
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ffmpeg \
        libva2 \
        libva-drm2 \
        intel-media-va-driver \
        vainfo \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .

# Directories — override via docker-compose volumes
RUN mkdir -p /media /output

EXPOSE 8090

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8090"]
