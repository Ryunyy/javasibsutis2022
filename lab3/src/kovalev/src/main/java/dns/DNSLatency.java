package dns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class DNSLatency {
    private static File inputFile = new File("csv/");
    private static String outputFilePath = "csv/DNSQueries__" + getCurrentDate() + ".csv";
    private static PingTimeList log = new PingTimeList();
    private static boolean checkListOnly = false;
    private static String[] ipToCheck = new String[0];

    /**
     * @return Текущая дата в формате "yyyy-MM-dd_HH-mm-ss".
     */
    private static String getCurrentDate()
    {
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        Date now = Calendar.getInstance().getTime();
        return df.format(now);
    }

    /**
     * @param list Строка формата [el1,el2,...]
     * @return Список из строк el1, el2, ...
     * @throws ListParsingError Нельзя извлечь элементы из полученного списка.
     */
    public static String[] extractListElements(String list)
    throws ListParsingError
    {
        String[] matches = Pattern.compile("[\\[,](.+?)(?=[\\],])")
                                  .matcher(list)
                                  .results()
                                  .map(match -> match.group(1))
                                  .toArray(String[]::new);

        if (matches.length == 0)
            throw new ListParsingError();

        return matches;
    }

    /**
     * Обработка поданных аргументов с использованием Commons CLI.
     * Даёт пользователю возможность задать свои пути ввода/вывода.
     * 
     * @param args Аргументы, поданные через CLI.
     */
    private static void parseArgs(String[] args) {
        Options options = new Options();
        options.addOption("i", "input", true, "Input File/Directory");
        options.addOption("l", "list", true, "List of Domains for Test; ex. '[localhost,8.8.8.8,1.1.1.1,ya.ru]'");
        options.addOption("o", "output", true, "Output CSV File");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            formatter.printHelp("DNSLatency", options);
            System.exit(1);
        }

        if (cmd.hasOption("list"))
            ipToCheck = extractListElements(cmd.getOptionValue("list"));
        if (cmd.hasOption("input"))
            inputFile = new File(cmd.getOptionValue("input"));
        if (cmd.hasOption("output"))
            outputFilePath = cmd.getOptionValue("output");

        if (cmd.hasOption("list") && !cmd.hasOption("input"))
            checkListOnly = true;
    }

    /**
     * Чтение файла логов по пути path с результатами Ping в формате CSV.
     * 
     * @param path Путь, по которому находится CSV-таблица.
     */
    private static void readCsv(Path path)
    throws IOException {
        File csvData = path.toFile();
        if (!csvData.exists() || csvData.isDirectory())
            return;
        
        try (CSVParser parser = CSVParser.parse(csvData,
                                                Charset.defaultCharset(),
                                                CSVFormat.Builder.create(CSVFormat.DEFAULT)
                                                                 .setDelimiter(";")
                                                                 .build()))
        {
            for (CSVRecord csvRecord : parser) {
                String ip = csvRecord.get(0);
                log.add(ip, Float.parseFloat(csvRecord.get(1)));
            }
        }
    }

    /**
     * Рекурсивное чтение логов в формате CSV.
     * Если ранее подан файл, читается только он.
     * Если подана директория (по умолчанию), из неё читаются рекурсивно файлы CSV.
     */
    private static void readCsvRecursive()
    {
        if (inputFile.isDirectory())
        {
            try {
                Files.walk(inputFile.toPath())
                     .filter(name -> name.toString().endsWith(".csv"))
                     .forEach(t -> {
                        try {
                            readCsv(t);
                        } catch (IOException e) {
                            System.err.println(e.getMessage());
                        }
                    });
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }
        }
        else
        {
            try {
                readCsv(inputFile.toPath());
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }
        }
    }

    /**
     * Используя сканнер sc, запрашивается количество серверов, которые введёт пользователь.
     * 
     * @param sc Сканнер на ввод
     * @return Количество серверов на проверку производительности.
     */
    private static int requestIpQueryNum(Scanner sc) {
        System.out.println("Введите число серверов DNS:");
        while (true) {
            try {
                return Integer.parseInt(sc.next());
            } catch (NumberFormatException e) {
                System.out.println("Введите корректное число!");
            }
        }
    }

    /**
     * Используя сканнер sc, запрашивается доменное имя сервера.
     * Если сервер уже проходил тестирование, запрашивается разрешение на сброс результата.
     * 
     * @param sc Сканнер на ввод
     * @param i Порядковый номер сервера
     * @return Строка доменного имени сервера, либо null
     */
    private static String requestDnsServer(Scanner sc, int i) {
        System.out.println("Введите адрес сервера DNS #" + String.valueOf(i) + ":");
        String ip = sc.next();
        PingResult query = log.findQuery(ip);
        if (query != null) {
            System.out.println("Сервер по адресу '" + ip + "' уже был протестирован. Его результат: " + query.getTime() + " мс.");
            System.out.println("Хотите сбросить результат? [y/N]");
            String answer = sc.next();
            if (answer.toLowerCase() == "y")
                log.remove(ip);
            else
                return null;
        }
        return ip;
    }

    /**
     * Печатает в консоль в формате таблицы результаты.
     */
    private static void printQueriesTable() {
        String header = String.format("%-16s | %s", "Домен", "Среднее время");
        System.out.println();
        System.out.println(header);
        System.out.println("-".repeat(header.length()));
        for (PingResult result : log.getQueriesLog())
            System.out.println(result.toString());
    }

    /**
     * Сохраняет результаты в файл вывода в формате CSV.
     */
    private static void saveQueriesCsv() {
        PingResult[] queriesLog = log.getQueriesLog();
        if ((queriesLog.length == 0) || outputFilePath.isEmpty())
            return;

        File parentFolder = new File(outputFilePath).getAbsoluteFile().getParentFile();
        parentFolder.mkdirs();

        try (PrintWriter out = new PrintWriter(outputFilePath)) {
            for (PingResult res : queriesLog) {
                out.println(res.getIp() + ";" + res.getTime());
            }
        } catch (FileNotFoundException e) {
            System.out.println("Ошибка записи в файл '" + outputFilePath + "'!");
        }
    }
    public static void main(String[] args)
    throws IOException {
        parseArgs(args);
        readCsvRecursive();

        try {
            for (String ip : ipToCheck) {
                System.out.println("[" + ip + "] Измеряем...");
                log.add(ip);
            }

            if (!checkListOnly) {
                try (Scanner scanner = new Scanner(System.in)) {
                    int ipQueryNum = requestIpQueryNum(scanner);
                    for (int i = 1; i <= ipQueryNum; i++) {
                        System.out.println();
                        String ip = requestDnsServer(scanner, i);
                        if (ip != null)
                            log.add(ip);
                    }
                }
            }
        } catch (InterruptedException | NoSuchElementException e) {
            System.out.println("Зафиксировали прерывание, выходим.");
        } finally {
            printQueriesTable();
            saveQueriesCsv();
        }
    }
}