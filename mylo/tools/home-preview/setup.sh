#!/usr/bin/env bash
# One-time setup: AndroidX collection sources, and Roboto (Android's system font) for faithful text.
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -d build/androidx/collection ]]; then
  git clone --quiet --depth 1 --filter=blob:none --sparse https://github.com/androidx/androidx build/androidx
  git -C build/androidx sparse-checkout set collection/collection/src
fi
fonts="$HOME/.local/share/fonts/roboto"
if [[ ! -f "$fonts/Roboto_400Regular.ttf" ]]; then
  mkdir -p "$fonts" build/fonts
  (cd build/fonts && npm pack @expo-google-fonts/roboto --silent >/dev/null && tar xzf expo-google-fonts-roboto-*.tgz)
  cp build/fonts/package/*/Roboto_*.ttf "$fonts/"
fi
# Compose Desktop asks fontconfig for "Noto Sans" on Linux; resolve it to Roboto like Android.
mkdir -p "$HOME/.config/fontconfig"
cat > "$HOME/.config/fontconfig/fonts.conf" <<'XML'
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>~/.local/share/fonts</dir>
  <match target="pattern">
    <test qual="any" name="family"><string>Noto Sans</string></test>
    <edit name="family" mode="assign" binding="strong"><string>Roboto</string></edit>
  </match>
</fontconfig>
XML
fc-cache -f >/dev/null 2>&1 || true
echo "Setup complete."
