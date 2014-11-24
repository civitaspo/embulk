package org.quickload.exec;

import java.util.List;
import java.util.Map;
import java.util.Iterator;
import com.google.common.collect.ImmutableList;
import javax.validation.constraints.NotNull;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.quickload.buffer.Buffer;
import org.quickload.config.Config;
import org.quickload.config.ConfigDefault;
import org.quickload.config.NextConfig;
import org.quickload.config.Task;
import org.quickload.config.TaskSource;
import org.quickload.config.ConfigSource;
import org.quickload.config.Report;
import org.quickload.channel.FileBufferInput;
import org.quickload.channel.PageOutput;
import org.quickload.record.Schema;
import org.quickload.record.Column;
import org.quickload.spi.InputPlugin;
import org.quickload.spi.ParserPlugin;
import org.quickload.spi.BasicParserPlugin;
import org.quickload.spi.ExecTask;
import org.quickload.spi.GuessPlugin;
import org.quickload.spi.ExecControl;

public class GuessExecutor
{
    private final Injector injector;
    private final ConfigSource systemConfig;

    @Inject
    public GuessExecutor(Injector injector,
            @ForSystemConfig ConfigSource systemConfig)
    {
        this.injector = injector;
        this.systemConfig = systemConfig;
    }

    public NextConfig run(ConfigSource config)
    {
        ExecTask exec = PluginExecutors.newExecTask(injector, config);
        return guess(exec, config);
    }

    protected InputPlugin newInputPlugin(ExecTask exec, ConfigSource guessInputConfig)
    {
        return exec.newPlugin(InputPlugin.class, guessInputConfig.get("type"));
    }

    public NextConfig guess(ExecTask exec, ConfigSource config)
    {
        try {
            return doGuess(exec, config);
        } catch (Throwable ex) {
            throw PluginExecutors.propagePluginExceptions(ex);
        }
    }

    private NextConfig doGuess(final ExecTask exec, ConfigSource config)
    {
        // repeat guessing upto 10 times
        NextConfig lastGuessed = new NextConfig();
        for (int i=0; i < 10; i++) {
            // include last-guessed config to run guess
            ConfigSource guessConfig = config.deepCopy().mergeRecursively(lastGuessed.deepCopy());

            // override in.parser.type so that FileInputPlugin creates GuessParserPlugin
            ConfigSource guessInputConfig = guessConfig.getObject("in");
            guessInputConfig.getObjectOrSetEmpty("parser").setString("type", "system_guess");

            // run FileInputPlugin
            final InputPlugin input = newInputPlugin(exec, guessInputConfig);
            NextConfig guessed;
            try {
                input.runInputTransaction(exec, guessInputConfig, new ExecControl() {
                    public List<Report> run(TaskSource inputTaskSource)
                    {
                        input.runInput(exec, inputTaskSource, 0, null);   // TODO add dummy PageOutput which throws "guess plugin works only with FileInputPlugin"
                        return null;
                    }
                });
                // unexpected
                throw new AssertionError("Guess executor must throw GuessedNoticeError");

            } catch (GuessedNoticeError error) {
                guessed = new NextConfig();
                guessed.getObjectOrSetEmpty("in").set("parser", error.getGuessedParserConfig());
            }

            // merge to the last-guessed config
            NextConfig nextGuessed = lastGuessed.deepCopy();
            lastGuessed.mergeRecursively(guessed);
            if (lastGuessed.equals(nextGuessed)) {
                // not changed
                return lastGuessed;
            }
        }
        return lastGuessed;
    }

    public static class GuessParserPlugin
            extends BasicParserPlugin
    {
        private interface PluginTask
                extends Task
        {
            @Config("guess_sample_size")
            @ConfigDefault("32768")
            public int getSampleSize();

            @Config("guess_plugins")
            @ConfigDefault("[\"gzip\",\"charset\",\"newline\",\"csv\"]")  // TODO require some plugins
            public List<JsonNode> getGuessPluginTypes();

            public ConfigSource getConfigSource();
            public void setConfigSource(ConfigSource config);
        }

        @Override
        public TaskSource getBasicParserTask(ExecTask exec, ConfigSource config)
        {
            PluginTask task = exec.loadConfig(config, PluginTask.class);

            task.setConfigSource(config);

            // set dummy schema to bypass ExecTask validation
            exec.setSchema(new Schema(ImmutableList.<Column>of()));

            return exec.dumpTask(task);
        }

        @Override
        public void runBasicParser(ExecTask exec,
                TaskSource taskSource, int processorIndex,
                FileBufferInput fileBufferInput, PageOutput pageOutput)
        {
            PluginTask task = exec.loadTask(taskSource, PluginTask.class);
            final int maxSampleSize = task.getSampleSize();
            final ConfigSource config = task.getConfigSource();

            // load guess plugins
            ImmutableList.Builder<GuessPlugin> builder = ImmutableList.builder();
            for (JsonNode guessType : task.getGuessPluginTypes()) {
                GuessPlugin guess = exec.newPlugin(GuessPlugin.class, guessType);
                builder.add(guess);
            }
            List<GuessPlugin> guesses = builder.build();

            // get samples
            Buffer sample = getSample(fileBufferInput, maxSampleSize);

            // guess
            NextConfig guessedParserConfig = new NextConfig();
            for (int i=0; i < guesses.size(); i++) {
                GuessPlugin guess = guesses.get(i);
                guessedParserConfig.mergeRecursively(guess.guess(exec, config, sample));
            }

            throw new GuessedNoticeError(guessedParserConfig);
        }

        public static Buffer getSample(FileBufferInput fileBufferInput, int maxSampleSize)
        {
            Buffer sample = Buffer.allocate(maxSampleSize);
            int sampleSize = 0;

            while (fileBufferInput.nextFile()) {
                for (Buffer buffer : fileBufferInput) {
                    if (sampleSize >= maxSampleSize) {
                        // skip remaining all buffers so that FileInputPlugin.runInput doesn't
                        // throw exceptions at channel.join()
                    } else {
                        sample.setBytes(sampleSize, buffer, 0, buffer.limit());
                        sampleSize += buffer.limit();
                    }
                    buffer.release();
                }
            }

            if (sampleSize == 0) {
                throw new RuntimeException("No input buffer to guess");  // TODO exception class
            }
            sample.limit(sampleSize);

            return sample;
        }
    }

    public static class GuessedNoticeError
            extends Error
    {
        private final NextConfig guessedParserConfig;

        public GuessedNoticeError(NextConfig guessedParserConfig)
        {
            this.guessedParserConfig = guessedParserConfig;
        }

        public NextConfig getGuessedParserConfig()
        {
            return guessedParserConfig;
        }
    }
}
