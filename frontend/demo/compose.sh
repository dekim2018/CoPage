#!/usr/bin/env bash
# 두 사람의 녹화를 좌우로 붙여 docs/demo/copage-demo.mp4 로 만든다 (D-51). ffmpeg 가 있어야 한다.
set -euo pipefail
cd "$(dirname "$0")/.."
left=$(ls -t demo-results/video-a/*.webm | head -1)
right=$(ls -t demo-results/video-b/*.webm | head -1)
mkdir -p ../docs/demo
ffmpeg -y -loglevel error -i "$left" -i "$right" \
  -filter_complex "[0:v]pad=iw+8:ih:0:0:white[l];[l][1:v]hstack=inputs=2,scale=1440:-2,fps=20[v]" \
  -map "[v]" -c:v libx264 -pix_fmt yuv420p -crf 30 -preset slow -movflags +faststart ../docs/demo/copage-demo.mp4
ls -lh ../docs/demo/copage-demo.mp4
