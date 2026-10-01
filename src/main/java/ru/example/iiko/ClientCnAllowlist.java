package ru.example.iiko;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Список разрешённых CommonName (CN) клиентских сертификатов, загружаемый из обычного
 * текстового файла (путь задаётся при запуске - см. {@code TLS_CLIENT_ALLOWED_CNS_FILE}
 * в {@link Main}), который администратор может редактировать сам.
 *
 * <p>Формат файла: один CN на строку. Пустые строки и строки, начинающиеся с {@code #},
 * игнорируются (комментарии). Пример:
 * <pre>
 *   # разрешённые клиенты
 *   partner-1
 *   partner-2
 * </pre>
 *
 * <p>Файл перечитывается "на лету" - перед каждой проверкой CN сравнивается время
 * последнего изменения файла ({@code Files.getLastModifiedTime}), и при изменении список
 * перечитывается заново. Это позволяет редактировать файл и видеть эффект без перезапуска
 * приложения, не опрашивая диск на каждый запрос дороже одного системного вызова
 * {@code stat}. Если файл временно недоступен для чтения (например, в процессе
 * редактирования), используется последний успешно загруженный список, а ошибка
 * логируется в stderr - один "битый" момент не должен обрушивать проверку клиентов.
 */
class ClientCnAllowlist {
    private final Path path;
    private volatile long lastLoadedModifiedMillis = -1;
    private final AtomicReference<Set<String>> allowed = new AtomicReference<>(Set.of());

    ClientCnAllowlist(Path path) {
        this.path = path;
    }

    /** Загружает файл немедленно; бросает исключение, если файл не читается - используется при старте. */
    static ClientCnAllowlist loadInitial(Path path) throws IOException {
        ClientCnAllowlist list = new ClientCnAllowlist(path);
        Set<String> cns = readFile(path);
        if (cns.isEmpty()) {
            throw new IOException("Файл " + path + " не содержит ни одного CommonName (пустые строки и строки с # игнорируются)");
        }
        list.allowed.set(cns);
        list.lastLoadedModifiedMillis = Files.getLastModifiedTime(path).toMillis();
        return list;
    }

    /** true, если cn есть в текущем (при необходимости - только что перечитанном) списке. */
    boolean isAllowed(String cn) {
        if (cn == null) {
            return false;
        }
        reloadIfChanged();
        return allowed.get().contains(cn);
    }

    /** Текущее число записей в списке - для диагностики/логов. */
    int size() {
        return allowed.get().size();
    }

    private void reloadIfChanged() {
        try {
            long currentModified = Files.getLastModifiedTime(path).toMillis();
            if (currentModified == lastLoadedModifiedMillis) {
                return;
            }
            Set<String> cns = readFile(path);
            allowed.set(cns);
            lastLoadedModifiedMillis = currentModified;
            if (cns.isEmpty()) {
                // Файл - источник истины: если в нём не осталось ни одного CN, значит
                // администратор сознательно (или по ошибке) закрыл доступ всем клиентам.
                // Не подменяем это решение старым списком - только громко предупреждаем.
                System.err.println("ВНИМАНИЕ: " + path + " перечитан и не содержит ни одного CommonName - " +
                        "ВСЕ клиентские сертификаты теперь будут отклонены, пока в файл не добавят хотя бы один CN");
            } else {
                System.out.println("Список разрешённых CommonName перечитан из " + path + " (" + cns.size() + " запис.)");
            }
        } catch (IOException e) {
            // Файл временно недоступен (например, редактируется) - работаем с последним
            // успешно загруженным списком, не роняем проверку запроса из-за этого.
            System.err.println("Не удалось перечитать " + path + ": " + e.getMessage() + " - использую предыдущий список");
        }
    }

    private static Set<String> readFile(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Set<String> result = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            result.add(trimmed);
        }
        return result;
    }
}
