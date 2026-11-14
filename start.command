#!/bin/bash
# Double-click this file in Finder to start Mac Storage Doctor.
cd "$(dirname "$0")"
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found. Install Apple's command line tools first:  xcode-select --install"
  read -r -p "Press Enter to close."
  exit 1
fi
exec python3 storage_doctor.py
