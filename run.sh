#!/usr/bin/env bash
#
# Запуск/остановка iiko-sync одной командой, без ручного набора java -jar с десятком
# переменных окружения. Все параметры (память JVM, режим, порт, TLS/mTLS, и т.д.)
# задаются в одном файле - config/iiko-sync.env (шаблон - config/iiko-sync.env.example,
# скопируйте его и отредактируйте под себя).
#
# Использование:
#   ./run.sh start     - запустить в фоне (nohup), записать PID в run/iiko-sync.pid
#   ./run.sh stop      - остановить процесс по PID-файлу
#   ./run.sh restart   - stop + start
#   ./run.sh status    - жив ли процесс
#   ./run.sh run       - запустить в текущем терминале (foreground, для отладки) -
#                        Ctrl+C останавливает приложение
#
# Необязательный второй аргумент - путь к своему env-файлу вместо config/iiko-sync.env:
#   ./run.sh start /путь/к/другому.env
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ACTION="${1:-}"
ENV_FILE="${2:-$SCRIPT_DIR/config/iiko-sync.env}"
LOG_DIR="$SCRIPT_DIR/logs"
RUN_DIR="$SCRIPT_DIR/run"
PID_FILE="$RUN_DIR/iiko-sync.pid"
LOG_FILE="$LOG_DIR/iiko-sync.log"

usage() {
    echo "Использование: $0 {start|stop|restart|status|run} [путь/к/иному.env]" >&2
    exit 1
}

if [[ -z "$ACTION" ]]; then
    usage
fi

# --- Находим jar-файл: сначала собранный shade-плагином (единственный без original- в имени) ---
JAR_PATH="$(find "$SCRIPT_DIR/target" -maxdepth 1 -name '*.jar' ! -name 'original-*' 2>/dev/null | head -1 || true)"
if [[ -z "$JAR_PATH" ]]; then
    echo "Не найден собранный jar в target/ - сначала выполните: mvn -DskipTests package" >&2
    exit 1
fi

# --- Загружаем конфигурацию из env-файла, если он есть; иначе работаем на дефолтах из Main.java ---
JVM_XMS="256m"
JVM_XMX="1024m"
JVM_EXTRA_OPTS=""

if [[ -f "$ENV_FILE" ]]; then
    echo "Конфигурация: $ENV_FILE"
    set -a   # все переменные, определённые при source, автоматически экспортируются
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
else
    echo "Файл $ENV_FILE не найден - используются значения по умолчанию (MODE=api, PORT=8080, без TLS)." >&2
    echo "Чтобы задать свои настройки: cp config/iiko-sync.env.example config/iiko-sync.env" >&2
fi

mkdir -p "$LOG_DIR" "$RUN_DIR"

# --- Собираем JVM-опции ---
JVM_OPTS=(-Xms"$JVM_XMS" -Xmx"$JVM_XMX" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8)
if [[ -n "$JVM_EXTRA_OPTS" ]]; then
    # намеренно без кавычек - даём shell'у разбить строку на отдельные флаги
    # shellcheck disable=SC2206
    JVM_OPTS+=($JVM_EXTRA_OPTS)
fi

is_running() {
    [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

do_start() {
    if is_running; then
        echo "Уже запущено (PID $(cat "$PID_FILE"))."
        exit 0
    fi
    echo "Запуск: java ${JVM_OPTS[*]} -jar $JAR_PATH"
    nohup java "${JVM_OPTS[@]}" -jar "$JAR_PATH" >>"$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    disown
    sleep 1
    if is_running; then
        echo "Запущено, PID $(cat "$PID_FILE"). Лог: $LOG_FILE"
    else
        echo "Процесс не поднялся - смотрите $LOG_FILE" >&2
        exit 1
    fi
}

do_stop() {
    if ! is_running; then
        echo "Не запущено."
        rm -f "$PID_FILE"
        return
    fi
    local pid
    pid="$(cat "$PID_FILE")"
    echo "Останавливаю PID $pid..."
    kill "$pid"
    for _ in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "Не остановилось за 10с, посылаю SIGKILL" >&2
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
    echo "Остановлено."
}

do_status() {
    if is_running; then
        echo "Работает, PID $(cat "$PID_FILE")."
    else
        echo "Не запущено."
        exit 1
    fi
}

do_run_foreground() {
    echo "Запуск в текущем терминале: java ${JVM_OPTS[*]} -jar $JAR_PATH"
    exec java "${JVM_OPTS[@]}" -jar "$JAR_PATH"
}

case "$ACTION" in
    start)   do_start ;;
    stop)    do_stop ;;
    restart) do_stop; do_start ;;
    status)  do_status ;;
    run)     do_run_foreground ;;
    *)       usage ;;
esac
