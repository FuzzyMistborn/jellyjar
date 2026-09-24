FROM python:3.14-slim

ARG PRESS_VERSION=dev
ENV PRESS_VERSION=$PRESS_VERSION

# jellyfin-ffmpeg instead of Debian's ffmpeg: it ships the GPU filters the hardware pipeline
# needs (scale_cuda/pad_cuda, scale_vaapi/pad_vaapi) and bundles its own libva plus the Intel
# iHD/i965 and AMD radeonsi VA-API drivers, so no non-free Debian driver packages are needed.
# NVENC/NVDEC need nothing in the image — the NVIDIA Container Toolkit injects the driver libs.
ADD https://repo.jellyfin.org/jellyfin_team.gpg.key /etc/apt/keyrings/jellyfin.asc
# ADD from a URL creates the file mode 600, which apt's unprivileged _apt user can't read
RUN chmod 644 /etc/apt/keyrings/jellyfin.asc && \
    . /etc/os-release && \
    printf 'Types: deb\nURIs: https://repo.jellyfin.org/debian\nSuites: %s\nComponents: main\nArchitectures: %s\nSigned-By: /etc/apt/keyrings/jellyfin.asc\n' \
        "$VERSION_CODENAME" "$(dpkg --print-architecture)" > /etc/apt/sources.list.d/jellyfin.sources && \
    apt-get update && \
    apt-get install -y --no-install-recommends jellyfin-ffmpeg8 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    ln -s /usr/lib/jellyfin-ffmpeg/ffmpeg /usr/local/bin/ffmpeg && \
    ln -s /usr/lib/jellyfin-ffmpeg/ffprobe /usr/local/bin/ffprobe && \
    ln -s /usr/lib/jellyfin-ffmpeg/vainfo /usr/local/bin/vainfo

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY main.py .
COPY static ./static

# Directories — override via docker-compose volumes
RUN mkdir -p /media /output /config

EXPOSE 8090

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8090"]
