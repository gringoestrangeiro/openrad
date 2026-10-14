#!/bin/sh
# Adds this extracted OpenRad folder to the current user's application menu.
# The file name and StartupWMClass match the desktop window's app_id
# (org.openrad.desktop), so Wayland and X11 docks show its icon. The launcher
# runs this folder's openrad-desktop by absolute path; re-run after moving it.
# Writes only under ${XDG_DATA_HOME:-~/.local/share}.
set -eu
here=$(cd "$(dirname "$0")" && pwd -P)
entry=org.openrad.desktop.desktop
if [ ! -x "$here/openrad-desktop" ] || [ ! -f "$here/$entry" ]; then
    echo "Run this script from the extracted OpenRad folder." >&2
    exit 1
fi
case $here in
    *[\"\`\$\\%]*|*"
"*)
        echo "Move OpenRad to a folder whose path has no quote, backtick, dollar, backslash, percent or newline." >&2
        exit 1
        ;;
esac
data=${XDG_DATA_HOME:-$HOME/.local/share}
mkdir -p "$data/applications" "$data/icons"
cp -R "$here/icons/." "$data/icons/"
temporary="$data/applications/.$entry.$$"
trap 'rm -f "$temporary"' EXIT
while IFS= read -r line; do
    case $line in
        Exec=*) printf 'Exec="%s/openrad-desktop"\n' "$here" ;;
        *) printf '%s\n' "$line" ;;
    esac
done < "$here/$entry" > "$temporary"
mv "$temporary" "$data/applications/$entry"
update-desktop-database "$data/applications" >/dev/null 2>&1 || true
echo "Installed $data/applications/$entry"
