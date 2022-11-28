import org.apache.commons.cli.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public class Grep {
    static class PathPatternsPair {
        Path path;
        String pattern;

        protected PathPatternsPair(Path path, String pattern) {
            this.path = path;
            this.pattern = pattern;
        }
    }

    static final String PROGRAM_NAME = "grep";
    static final int AVERAGE_CHARS_PER_LINE = 100;
    static final String WORD_BOUNDARY = "\\b";
    static final HelpFormatter FORMATTER = new HelpFormatter();
    static CommandLine cmd = null;

    public static void main(String[] args) {
        Options options = intializeOptions();
        CommandLineParser cmdParser = new DefaultParser();
        Pattern pattern = null;
        try {
            cmd = cmdParser.parse(options, args);
        } catch (ParseException e) {
            FORMATTER.printHelp(PROGRAM_NAME, options, true);
            return;
        }

        if (cmd.hasOption("help")) {
            FORMATTER.printHelp(PROGRAM_NAME, options, true);
            return;
        }

        try {
            int optionForPatternCompiler = cmd.hasOption("i") ? Pattern.CASE_INSENSITIVE : 0;
            String patternString = cmd.getOptionValue("sP");
            if (cmd.hasOption("w"))
                patternString = WORD_BOUNDARY + patternString + WORD_BOUNDARY;

            pattern = Pattern.compile(patternString, optionForPatternCompiler);
        } catch (PatternSyntaxException ignored) {
            System.out.println("The provided search pattern is invalid");
            FORMATTER.printHelp(PROGRAM_NAME, options, true);
            return;
        }

        File[] files = findFiles(cmd.getOptionValue("fP"));

        //Check to see if files were found
        if (files == null) {
            FORMATTER.printHelp(PROGRAM_NAME, options, true);
            return;
        }

        for (File file : files) {
            try {
                printFromFile(file, pattern);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static Options intializeOptions() {
        Options options = new Options();
        options.addOption("i", false, "Ignore case sensitivity");
        options.addOption("l", false, "Show only file names");
        options.addOption("n", false, "Show line numbers");
        options.addOption("R", false, "Recursively match files");
        options.addOption("h", false, "Do not show the names of the files");
        options.addOption("w", false, "Match only whole words");
        options.addOption("c", false, "Show the number of matches found");
        options.addOption("v", false, "Show lines with no matches");
        options.addOption("A", true, "Show this number of lines after a match");
        options.addOption("B", true, "Show this number of lines before a match");
        options.addOption("help", false, "Show the usage pattern and options");
        Option searchPatternOption = Option.builder("sP").argName("search pattern").required().hasArg().desc("Pattern for matching in files").build();
        options.addOption(searchPatternOption);
        Option filePattern = Option.builder("fP").argName("files pattern").required().hasArgs().valueSeparator(';').desc("Pattern for searching files").build();
        options.addOption(filePattern);
        return options;
    }

    static PathPatternsPair getPathAndPattern(String filesString) {
        Path workingDirectory = new File(System.getProperty("user.dir")).toPath();

        //check if the pattern is just a wildcard
        if (filesString.equals(".") || filesString.equals("*"))
            return new PathPatternsPair(workingDirectory, "*");

        //check if the input is a path and if so use it otherwise return the current directory
        int indexOfSlash = filesString.lastIndexOf("\\");
        try {
            if (indexOfSlash == -1) {
                return new PathPatternsPair(workingDirectory, filesString);
            } else {
                String pattern = filesString.substring(indexOfSlash + 1);
                Path path = Path.of(filesString.substring(0, indexOfSlash)).toAbsolutePath();
                return new PathPatternsPair(path, pattern);
            }
        } catch (InvalidPathException ignored) {
            System.out.println("No such directory or file found");
            return null;
        }
    }

    static File[] findFiles(String filesString) {
        PathPatternsPair pair = getPathAndPattern(filesString);

        //Check to see if a file or directory was found
        if (pair == null)
            return null;

        File workingDirectory = pair.path.toFile();

        //Check if it points to a file and if so return it
        if (!workingDirectory.isDirectory())
            return new File[]{new File(workingDirectory.toURI())};

        //Check if the search should be recursive
        int depthOfFileSearch = cmd.hasOption("R") ? Integer.MAX_VALUE : 1;

        //Getting and matching the files
        try (Stream<Path> files = Files.walk(workingDirectory.toPath(), depthOfFileSearch, FileVisitOption.FOLLOW_LINKS)) {
            Iterator<Path> pathsIterator = files.iterator();
            ArrayList<File> fileArrayList = new ArrayList<>();

            //Match the files
            while (pathsIterator.hasNext()) {
                Path path = pathsIterator.next();
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pair.pattern);
                if (matcher.matches(path.getFileName())) {
                    if (!path.toFile().isDirectory())
                        fileArrayList.add(path.toFile());
                    else
                        System.out.println(path.getFileName() + " is a directory");
                }
            }
            return fileArrayList.toArray(File[]::new);
        } catch (Exception e) {
            System.out.println("Error has occurred while trying to load the files");
            return null;
        }
    }

    static String readyLine(String fileName, String line, int numOfLines) {
        //Check if it should display the lines
        if (!cmd.hasOption("c") && !cmd.hasOption("v")) {
            StringBuilder sb = new StringBuilder();
            //Check if the filename should be displayed
            if (!cmd.hasOption("h") || cmd.hasOption("l"))
                sb.append(fileName).append(":");

            //Check if lines should be displayed
            if (!cmd.hasOption('l')) {
                //Check if the number of the line should be displayed
                if (cmd.hasOption('n'))
                    sb.append(numOfLines).append(":");
                sb.append(line);
            }
            return sb.toString();
        }
        return "";
    }

    static void printFromFile(File file, Pattern pattern) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        int numOfLines = 0;
        int numOfMatches = 0;
        String[] passedLines = null;
        int passedLinesIndex = 0;

        if (cmd.hasOption("B")) {
            int linesToDisplayBefore = Integer.parseInt(cmd.getOptionValue("B"));
            passedLines = new String[linesToDisplayBefore];
        }

        String line = reader.readLine();
        while (line != null) {
            Matcher matcher = pattern.matcher(line);
            numOfLines++;
            //Check for a match
            if (!matcher.find()) {
                //Check if lines without matches should be displayed
                if (cmd.hasOption("v"))
                    System.out.println(readyLine(file.getName(), line, numOfLines));

                if (cmd.hasOption("B")) {
                    if (passedLinesIndex >= passedLines.length)
                        passedLinesIndex = 0;
                    passedLines[passedLinesIndex++] = readyLine(file.getName(), line, numOfLines);
                }

                line = reader.readLine();
                continue;
            }

            numOfMatches++;

            //Show lines before match if needed
            if (cmd.hasOption("B")) {
                int linesBeforeMatch = Integer.parseInt(cmd.getOptionValue("B"));

                for (int i = 0; i < linesBeforeMatch; i++) {
                    if (passedLinesIndex >= passedLines.length)
                        passedLinesIndex = 0;
                    System.out.println(passedLines[passedLinesIndex++]);
                }

                if (passedLinesIndex >= passedLines.length)
                    passedLinesIndex = 0;
                passedLines[passedLinesIndex++] = line;
            }

            //Display the matched line
            System.out.println(readyLine(file.getName(), line, numOfLines));
            if (cmd.hasOption("B") && !cmd.hasOption("A"))
                System.out.println("-----------");

            //Show lines after match if needed
            if (cmd.hasOption("A")) {
                int linesAfterMatch = Integer.parseInt(cmd.getOptionValue("A"));
                int markedNumOfLines = numOfLines;

                reader.mark(linesAfterMatch * AVERAGE_CHARS_PER_LINE);
                for (int i = 0; i < linesAfterMatch; i++) {
                    markedNumOfLines++;
                    line = reader.readLine();
                    System.out.println(readyLine(file.getName(), line, markedNumOfLines));
                }
                reader.reset();
                System.out.println("-----------");
            }

            line = reader.readLine();
        }
        //Check if the number of matches should be displayed
        if (cmd.hasOption("c"))
            System.out.println(file.getName() + ": " + numOfMatches);
    }
}
