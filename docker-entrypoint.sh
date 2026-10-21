#!/bin/sh
# Starts PayBridge inside the container.
#
# Render (and most hosts) give the database as one address like
#     postgresql://USER:PASSWORD@HOST:5432/DBNAME
# but the Java database driver wants
#     jdbc:postgresql://HOST:5432/DBNAME   (with the user and password given SEPARATELY).
# If the user:password part is left in the address, the driver mistakes it for part of the host name and cannot connect.
# This script converts the address and moves the user and password into their own settings (unless those were already given).
set -e

# Addresses written as postgresql://user:pass@host/db percent-encode special characters in the user and password
# (an @ becomes %40, a / becomes %2F). The database wants the real characters, so undo that before using them.
urldecode() {
  # turn each %XX into a \xXX escape, then let printf expand the escapes
  printf '%b' "$(printf '%s' "$1" | sed 's/%\([0-9A-Fa-f][0-9A-Fa-f]\)/\\x\1/g')"
}

raw="${SPRING_DATASOURCE_URL:-$DATABASE_URL}"

if [ -n "$raw" ]; then
  case "$raw" in
    jdbc:*)
      url="$raw"
      ;;
    *)
      rest="${raw#*://}"
      creds=""
      case "$rest" in
        *@*)
          creds="${rest%%@*}"
          rest="${rest#*@}"
          ;;
      esac
      url="jdbc:postgresql://$rest"
      if [ -n "$creds" ]; then
        if [ -z "$SPRING_DATASOURCE_USERNAME" ]; then
          SPRING_DATASOURCE_USERNAME="$(urldecode "${creds%%:*}")"
          export SPRING_DATASOURCE_USERNAME
        fi
        case "$creds" in
          *:*)
            if [ -z "$SPRING_DATASOURCE_PASSWORD" ]; then
              SPRING_DATASOURCE_PASSWORD="$(urldecode "${creds#*:}")"
              export SPRING_DATASOURCE_PASSWORD
            fi
            ;;
        esac
      fi
      ;;
  esac
  SPRING_DATASOURCE_URL="$url"
  export SPRING_DATASOURCE_URL
fi

# JAVA_OPTS is meant to be split into separate words, so it is deliberately not quoted.
# shellcheck disable=SC2086
exec java $JAVA_OPTS -Dserver.port="${PORT:-8080}" -jar /app/app.jar
