/**
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.graylog2.streams;

import com.codahale.metrics.InstrumentedExecutorService;
import com.codahale.metrics.InstrumentedThreadFactory;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.SimpleTimeLimiter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.TimeLimiter;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import org.graylog2.Configuration;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugin.streams.StreamRule;
import org.graylog2.streams.matchers.StreamRuleMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Stream routing engine to select matching streams for a message.
 *
 * This class is NOT thread-safe! Use one instance per thread.
 */
public class StreamRouterEngine {
    private static final Logger LOG = LoggerFactory.getLogger(StreamRouterEngine.class);

    private final List<Stream> streams;
    private final Configuration configuration;
    private final MetricRegistry metricRegistry;
    private final TimeLimiter timeLimiter;

    private final Map<String, List<Rule>> presenceRules = Maps.newHashMap();
    private final Map<String, List<Rule>> exactRules = Maps.newHashMap();
    private final Map<String, List<Rule>> greaterRules = Maps.newHashMap();
    private final Map<String, List<Rule>> smallerRules = Maps.newHashMap();
    private final Map<String, List<Rule>> regexRules = Maps.newHashMap();

    private final Set<String> presenceFields = Sets.newHashSet();
    private final Set<String> exactFields = Sets.newHashSet();
    private final Set<String> greaterFields = Sets.newHashSet();
    private final Set<String> smallerFields = Sets.newHashSet();
    private final Set<String> regexFields = Sets.newHashSet();

    public interface Factory {
        public StreamRouterEngine create(List<Stream> streams);
    }

    @Inject
    public StreamRouterEngine(@Assisted List<Stream> streams,
                              Configuration configuration,
                              MetricRegistry metricRegistry) {
        this.configuration = configuration;
        this.streams = streams;
        this.metricRegistry = metricRegistry;
        this.timeLimiter = new SimpleTimeLimiter(executorService());

        for (final Stream stream : streams) {
            for (final StreamRule streamRule : stream.getStreamRules()) {
                try {
                    final Rule rule = new Rule(stream, streamRule);

                    switch (streamRule.getType()) {
                        case EXACT:
                            addRule(exactRules, exactFields, streamRule.getField(), rule);
                            break;
                        case GREATER:
                            addRule(greaterRules, greaterFields, streamRule.getField(), rule);
                            break;
                        case SMALLER:
                            addRule(smallerRules, smallerFields, streamRule.getField(), rule);
                            break;
                        case REGEX:
                            addRule(regexRules, regexFields, streamRule.getField(), rule);
                            break;
                        case PRESENCE:
                            addRule(presenceRules, presenceFields, streamRule.getField(), rule);
                            break;
                    }
                } catch (InvalidStreamRuleTypeException e) {
                    LOG.warn("Invalid stream rule type. Skipping matching for this rule. " + e.getMessage(), e);
                }
            }
        }
    }

    private ExecutorService executorService() {
        return new InstrumentedExecutorService(Executors.newCachedThreadPool(threadFactory()), metricRegistry);
    }

    private ThreadFactory threadFactory() {
        return new InstrumentedThreadFactory(new ThreadFactoryBuilder()
                .setNameFormat("stream-router-engine-%d")
                .setDaemon(true)
                .build(), metricRegistry);
    }

    /**
     * Returns the list of streams that are processed by the engine.
     *
     * @return the list of streams
     */
    public List<Stream> getStreams() {
        return streams;
    }

    /**
     * Returns a list of matching streams for the given message.
     *
     * @param message
     * @return the list of matching streams
     */
    public List<Stream> match(Message message) {
        final Map<Stream, StreamMatch> matches = Maps.newHashMap();
        final List<Stream> result = Lists.newArrayList();
        final Map<String, Object> messageFields = message.getFields();
        final Set<String> messageKeys = messageFields.keySet();

        // Execute the rules ordered by complexity. (fast rules first)
        matchRules(message, presenceFields, presenceRules, matches);
        // Only pass an intersection of the rules fields to avoid checking every field! (does not work for presence matching)
        matchRules(message, Sets.intersection(messageKeys, exactFields), exactRules, matches);
        matchRules(message, Sets.intersection(messageKeys, greaterFields), greaterRules, matches);
        matchRules(message, Sets.intersection(messageKeys, smallerFields), smallerRules, matches);
        matchRules(message, Sets.intersection(messageKeys, regexFields), regexRules, matches);

        for (Map.Entry<Stream, StreamMatch> entry : matches.entrySet()) {
            if (entry.getValue().isMatched()) {
                result.add(entry.getKey());
            }
        }

        return result;
    }

    /**
     * Returns a list of stream rule matches. Can be used to test streams and stream rule matches.
     * This is meant for testing, do NOT use in production processing pipeline! (use {@link #match(org.graylog2.plugin.Message) match} instead)
     *
     * @param message the message to match streams on
     */
    public List<StreamTestMatch> testMatch(Message message) {
        final List<StreamTestMatch> matches = Lists.newArrayList();

        for (final Stream stream : streams) {
            final StreamTestMatch match = new StreamTestMatch(stream);

            for (final StreamRule streamRule : stream.getStreamRules()) {
                try {
                    final Rule rule = new Rule(stream, streamRule);
                    match.addRule(rule);
                } catch (InvalidStreamRuleTypeException e) {
                    LOG.warn("Invalid stream rule type. Skipping matching for this rule. " + e.getMessage(), e);
                }
            }

            match.matchMessage(message);

            matches.add(match);
        }

        return matches;
    }

    private void matchRules(Message message, Set<String> fields, Map<String, List<Rule>> rules, Map<Stream, StreamMatch> matches) {
        for (String field : fields) {
            for (Rule rule : rules.get(field)) {
                final Stream match = rule.match(message);

                if (match != null) {
                    if (! matches.containsKey(match)) {
                        matches.put(match, new StreamMatch(match));
                    }
                    matches.get(match).increment();
                }
            }
        }
    }

    private void addRule(Map<String, List<Rule>> rules, Set<String> fields, String field, Rule rule) {
        fields.add(field);

        if (! rules.containsKey(field)) {
            rules.put(field, Lists.newArrayList(rule));
        } else {
            rules.get(field).add(rule);
        }
    }

    private class StreamMatch {
        private final int ruleCount;
        private int matches = 0;

        public StreamMatch(Stream stream) {
            this.ruleCount = stream.getStreamRules().size();
        }

        public void increment() {
            matches++;
        }

        public boolean isMatched() {
            // If a stream has multiple stream rules, all of the rules have to match.
            return ruleCount == matches;
        }
    }

    private class Rule {
        private final Stream stream;
        private final StreamRule rule;
        private final StreamRuleMatcher matcher;

        public Rule(Stream stream, StreamRule rule) throws InvalidStreamRuleTypeException {
            this.stream = stream;
            this.rule = rule;
            this.matcher = StreamRuleMatcherFactory.build(rule.getType());
        }

        public Stream match(Message message) {
            if (matcher.match(message, rule)) {
                return stream;
            } else {
                return null;
            }
        }

        public StreamRule getStreamRule() {
            return rule;
        }
    }

    /**
     * Contains matching results for a stream. This is useful for testing to see if a stream matches and which
     * rules matched.
     */
    public static class StreamTestMatch {
        private final Stream stream;
        private final List<Rule> rules = Lists.newArrayList();

        private final Map<StreamRule, Boolean> matches = Maps.newHashMap();

        public StreamTestMatch(Stream stream) {
            this.stream = stream;
        }

        public void addRule(Rule rule) {
            rules.add(rule);
        }

        public void matchMessage(Message message) {
            for (Rule rule : rules) {
                final Stream match = rule.match(message);
                matches.put(rule.getStreamRule(), (match != null && match.equals(stream)));
            }
        }

        public boolean isMatched() {
            return !matches.isEmpty() && !matches.values().contains(false);
        }

        public Stream getStream() {
            return stream;
        }

        public Map<StreamRule, Boolean> getMatches() {
            return matches;
        }
    }
}
