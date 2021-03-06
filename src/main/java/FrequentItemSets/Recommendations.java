package FrequentItemSets;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.operators.Order;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.operators.DataSource;
import org.apache.flink.api.java.tuple.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import java.util.*;

/**
 * @author Dzenan Softic
 * @author Tanguy Racinet
 */
public class Recommendations {

    public static void main(String[] args) throws Exception {

		ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        DataSource<String> SetsInput = env.readTextFile(Config.pathToFrequentSets());
        DataSource<String> UserInput = env.readTextFile(Config.pathToTestSet());

        // frequent sets: Tuple3<cards, suport%, victoryRatio>
        DataSet<Tuple3<String, Double, Double>> frequentSets = SetsInput.map(new FrequentSetsReader());
        // user cards: Tuple2<card, cardCount>
        DataSet<Tuple2<String, Integer>> UserCards = UserInput.flatMap(new UserReader());

        // Recommendations: Tuple3<card, score(support*ratio), usersCards>
        DataSet<Tuple3<String, Double, Integer>> Recommendations = frequentSets
                // generates item sets for that user
                .map(new RecommendationSystem()).withBroadcastSet(UserCards, "UserCards")
                // removes sets not frequent enough (Config.getScoreThreshold)
                .filter(new FilterNonpresent())
                // print out the top X sets with 3, 2, 1 and 0 cards from the user
                .groupBy(2).sortGroup(2, Order.DESCENDING).first(3);

        Recommendations.writeAsCsv(Config.pathToRecommendations(), "\n", "\t", org.apache.flink.core.fs.FileSystem.WriteMode.OVERWRITE);

        env.execute();
    }

	private static class FrequentSetsReader implements MapFunction<String, Tuple3<String, Double, Double>> {
        /**
         * @param line: 
         * @return Tuple3<cards, suport%, victoryRatio>
         */
        @Override
        public Tuple3<String, Double, Double> map(String line) throws Exception {
            String[] elements = line.split("\t");
            return new Tuple3<String, Double, Double>(elements[0], Double.parseDouble(elements[1]), Double.parseDouble(elements[2]));
        }
    }

	/**
	 * Read userinput and concatenate the cards into one string
	 * Collector: Tuple2<card, cardCount>
	 */
    private static class UserReader implements FlatMapFunction<String, Tuple2<String, Integer>> {
        /**
         * @param line: user input
         * @param collector: Tuple2<card, cardCount>
         */
        @Override
        public void flatMap(String line, Collector<Tuple2<String, Integer>> collector) throws Exception {
            try {
                String[] tokens = line.split("\t");
                String player_name = tokens[0];
                String[] eventResult = tokens[1].split(",");
                String event = tokens[2];
                String arch = tokens[3];
                String[] terms = tokens[4].split("&&");

                String[] card;
                for (String term : terms) {
                    card = term.split("\\$\\$");
                    collector.collect(new Tuple2<String, Integer>(card[1], Integer.parseInt(card[0])));
                }
            }
            catch(Exception e){
                System.out.println("warning, format problem: "+line);
            }
        }
    }
	
    public static class RecommendationSystem extends RichMapFunction<Tuple3<String, Double, Double>, Tuple3<String, Double, Integer>> {

        final private List<String> cards = new ArrayList<String>();

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);

            Collection<Tuple2<String, Integer>> userCards = getRuntimeContext().getBroadcastVariable("UserCards");
            for (Tuple2<String, Integer> card : userCards)
                cards.add(card.f0);
        }

        /**
         * @param line: Tuple3<card, support, victoryRatio>
         * @return Tuple3<card, score(support*ratio), usersCards>
         */
        @Override
        public Tuple3<String, Double, Integer> map(Tuple3<String, Double, Double> line) throws Exception {
            String[] tokens = line.f0.split("&&");
            Integer number = 0;

            for(String card : cards){
                number += Arrays.asList(tokens).contains(card)? 1 : 0;
            }
            return new Tuple3<String, Double, Integer>(line.f0, line.f1 * line.f2, number);
        }
    }
	
	private static class FilterNonpresent implements org.apache.flink.api.common.functions.FilterFunction<Tuple3<String, Double, Integer>> {
        /**
         * @param line: Tuple3<card, score(support*ratio), usersCards>
         * @return boolean: score > threshold or contains cards from input
         */
        @Override
        public boolean filter(Tuple3<String, Double, Integer> line) throws Exception {
            return line.f1 > Config.getScoreThreshold() || line.f2 > 1;
        }
    }
}
