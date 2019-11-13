package com.redislabs.demo.brewdis;

import static com.redislabs.demo.brewdis.Field.BREWERY_ICON;
import static com.redislabs.demo.brewdis.Field.BREWERY_ID;
import static com.redislabs.demo.brewdis.Field.BREWERY_NAME;
import static com.redislabs.demo.brewdis.Field.CATEGORY_ID;
import static com.redislabs.demo.brewdis.Field.CATEGORY_NAME;
import static com.redislabs.demo.brewdis.Field.COUNT;
import static com.redislabs.demo.brewdis.Field.FOOD_PAIRINGS;
import static com.redislabs.demo.brewdis.Field.LOCATION;
import static com.redislabs.demo.brewdis.Field.PRODUCT_DESCRIPTION;
import static com.redislabs.demo.brewdis.Field.PRODUCT_ID;
import static com.redislabs.demo.brewdis.Field.PRODUCT_LABEL;
import static com.redislabs.demo.brewdis.Field.PRODUCT_NAME;
import static com.redislabs.demo.brewdis.Field.STORE_ID;
import static com.redislabs.demo.brewdis.Field.STYLE_ID;
import static com.redislabs.demo.brewdis.Field.STYLE_NAME;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redislabs.demo.brewdis.BrewdisController.BrewerySuggestionPayload;
import com.redislabs.demo.brewdis.BrewdisController.Category;
import com.redislabs.demo.brewdis.BrewdisController.Style;
import com.redislabs.lettusearch.RediSearchCommands;
import com.redislabs.lettusearch.RediSearchUtils;
import com.redislabs.lettusearch.RediSearchUtils.Info;
import com.redislabs.lettusearch.StatefulRediSearchConnection;
import com.redislabs.lettusearch.aggregate.AggregateOptions;
import com.redislabs.lettusearch.aggregate.AggregateResults;
import com.redislabs.lettusearch.aggregate.Group;
import com.redislabs.lettusearch.aggregate.Limit;
import com.redislabs.lettusearch.aggregate.Order;
import com.redislabs.lettusearch.aggregate.Sort;
import com.redislabs.lettusearch.aggregate.SortProperty;
import com.redislabs.lettusearch.aggregate.reducer.CountDistinct;
import com.redislabs.lettusearch.search.DropOptions;
import com.redislabs.lettusearch.search.Schema;
import com.redislabs.lettusearch.search.field.GeoField;
import com.redislabs.lettusearch.search.field.NumericField;
import com.redislabs.lettusearch.search.field.PhoneticMatcher;
import com.redislabs.lettusearch.search.field.TagField;
import com.redislabs.lettusearch.search.field.TextField;
import com.redislabs.riot.cli.ProcessorOptions;
import com.redislabs.riot.cli.TransferOptions;
import com.redislabs.riot.cli.file.FileImportCommand;
import com.redislabs.riot.cli.file.FileReaderOptions;
import com.redislabs.riot.cli.redis.KeyOptions;
import com.redislabs.riot.cli.redis.RediSearchCommandOptions;
import com.redislabs.riot.cli.redis.RedisConnectionOptions;
import com.redislabs.riot.cli.redis.RedisWriterOptions;

import io.lettuce.core.RedisCommandExecutionException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DataLoader implements InitializingBean {

	@Autowired
	private StatefulRediSearchConnection<String, String> connection;
	@Autowired
	private BrewdisConfig config;
	@Getter
	private List<Category> categories;
	@Getter
	private Map<String, List<Style>> styles = new HashMap<>();
	private List<String> stopwords;

	@Override
	public void afterPropertiesSet() throws Exception {
		this.stopwords = Files.readAllLines(Paths.get(ClassLoader.getSystemResource("english_stopwords.txt").toURI()));
	}

	public void execute() throws IOException, URISyntaxException {
		loadStores();
		loadProducts();
		loadBreweries();
		loadCategoriesAndStyles();
		loadFoodPairings();
	}

	private void loadStores() {
		RediSearchCommands<String, String> commands = connection.sync();
		String index = config.getStore().getIndex();
		try {
			Info info = RediSearchUtils.getInfo(commands.ftInfo(index));
			if (info.getNumDocs() >= config.getStore().getCount()) {
				log.info("Found {} stores - skipping load", info.getNumDocs());
				return;
			}
			commands.drop(index, DropOptions.builder().build());
		} catch (RedisCommandExecutionException e) {
			if (!e.getMessage().equals("Unknown Index name")) {
				throw e;
			}
		}
		Schema schema = Schema.builder().field(TagField.builder().name(STORE_ID).sortable(true).build())
				.field(TextField.builder().name("description").build())
				.field(TagField.builder().name("market").sortable(true).build())
				.field(TagField.builder().name("parent").sortable(true).build())
				.field(TextField.builder().name("address").build())
				.field(TextField.builder().name("city").sortable(true).build())
				.field(TagField.builder().name("country").sortable(true).build())
				.field(TagField.builder().name("inventoryAvailableToSell").sortable(true).build())
				.field(TagField.builder().name("isDefault").sortable(true).build())
				.field(TagField.builder().name("preferred").sortable(true).build())
				.field(NumericField.builder().name("latitude").sortable(true).build())
				.field(GeoField.builder().name(LOCATION).build())
				.field(NumericField.builder().name("longitude").sortable(true).build())
				.field(TagField.builder().name("rollupInventory").sortable(true).build())
				.field(TagField.builder().name("state").sortable(true).build())
				.field(TagField.builder().name("type").sortable(true).build())
				.field(TagField.builder().name("postalCode").sortable(true).build()).build();
		commands.create(index, schema);
		FileImportCommand command = new FileImportCommand();
		FileReaderOptions readerOptions = new FileReaderOptions();
		readerOptions.setPath(config.getStore().getUrl());
		readerOptions.setHeader(true);
		command.setFileReaderOptions(readerOptions);
		ProcessorOptions processorOptions = new ProcessorOptions();
		processorOptions.getFields().put(LOCATION, "#geo(longitude,latitude)");
		command.setProcessorOptions(processorOptions);
		RedisWriterOptions writerOptions = new RedisWriterOptions();
		RediSearchCommandOptions searchOptions = new RediSearchCommandOptions();
		searchOptions.setIndex(index);
		KeyOptions keyOptions = new KeyOptions();
		keyOptions.setKeyspace("store");
		keyOptions.setKeys(STORE_ID);
		writerOptions.setKeyOptions(keyOptions);
		writerOptions.setRediSearchCommandOptions(searchOptions);
		command.setRedisWriterOptions(writerOptions);
		command.execute("stores-import", new RedisConnectionOptions());
	}

	private void loadProducts() {
		RediSearchCommands<String, String> commands = connection.sync();
		String index = config.getProduct().getIndex();
		try {
			Info info = RediSearchUtils.getInfo(commands.ftInfo(index));
			if (info.getNumDocs() >= config.getProduct().getLoad().getCount()) {
				log.info("Found {} products - skipping load", info.getNumDocs());
				return;
			}
			commands.drop(index, DropOptions.builder().build());
		} catch (RedisCommandExecutionException e) {
			if (!e.getMessage().equals("Unknown Index name")) {
				throw e;
			}
		}
		Schema schema = Schema.builder().field(TagField.builder().name(PRODUCT_ID).sortable(true).build())
				.field(TextField.builder().name(PRODUCT_NAME).sortable(true).build())
				.field(TextField.builder().name(PRODUCT_DESCRIPTION).matcher(PhoneticMatcher.English).build())
				.field(TagField.builder().name(PRODUCT_LABEL).build())
				.field(TagField.builder().name(CATEGORY_ID).sortable(true).build())
				.field(TextField.builder().name(CATEGORY_NAME).build())
				.field(TagField.builder().name(STYLE_ID).sortable(true).build())
				.field(TextField.builder().name(STYLE_NAME).build())
				.field(TagField.builder().name(BREWERY_ID).sortable(true).build())
				.field(TextField.builder().name(BREWERY_NAME).build())
				.field(TextField.builder().name(FOOD_PAIRINGS).sortable(true).build())
				.field(TagField.builder().name("isOrganic").sortable(true).build())
				.field(NumericField.builder().name("abv").sortable(true).build())
				.field(NumericField.builder().name("ibu").sortable(true).build()).build();
		commands.create(index, schema);
		FileImportCommand command = new FileImportCommand();
		TransferOptions transferOptions = new TransferOptions();
		if (config.getProduct().getLoad().getSleep() != null) {
			transferOptions.setSleep(config.getProduct().getLoad().getSleep());
		}
		command.setTransferOptions(transferOptions);
		FileReaderOptions readerOptions = new FileReaderOptions();
		readerOptions.setPath(config.getProduct().getUrl());
		command.setFileReaderOptions(readerOptions);
		ProcessorOptions processorOptions = new ProcessorOptions();
		processorOptions.addField(PRODUCT_ID, "id");
		processorOptions.addField(PRODUCT_LABEL, "containsKey('labels')");
		processorOptions.addField(CATEGORY_ID, "style.category.id");
		processorOptions.addField(CATEGORY_NAME, "style.category.name");
		processorOptions.addField(STYLE_NAME, "style.shortName");
		processorOptions.addField(STYLE_ID, "style.id");
		processorOptions.addField(BREWERY_ID, "containsKey('breweries')?breweries[0].id:null");
		processorOptions.addField(BREWERY_NAME, "containsKey('breweries')?breweries[0].nameShortDisplay:null");
		processorOptions.addField(BREWERY_ICON,
				"containsKey('breweries')?breweries[0].containsKey('images')?breweries[0].get('images').get('icon'):null:null");
		command.setProcessorOptions(processorOptions);
		RedisWriterOptions writerOptions = new RedisWriterOptions();
		RediSearchCommandOptions rediSearchCommandOptions = new RediSearchCommandOptions();
		rediSearchCommandOptions.setIndex(index);
		writerOptions.setRediSearchCommandOptions(rediSearchCommandOptions);
		KeyOptions keyOptions = new KeyOptions();
		keyOptions.setKeyspace("product");
		keyOptions.setKeys(PRODUCT_ID);
		writerOptions.setKeyOptions(keyOptions);
		command.setRedisWriterOptions(writerOptions);
		command.execute("products-import", new RedisConnectionOptions());
	}

	private void loadCategoriesAndStyles() {
		log.info("Loading categories");
		RediSearchCommands<String, String> commands = connection.sync();
		String index = config.getProduct().getIndex();
		AggregateResults<String, String> results = commands.aggregate(index, "*",
				AggregateOptions.builder().load(CATEGORY_NAME)
						.operation(Group.builder().property(CATEGORY_ID).property(CATEGORY_NAME)
								.reduce(CountDistinct.builder().property(PRODUCT_ID).as(COUNT).build()).build())
						.build());
		this.categories = results.stream()
				.map(r -> Category.builder().id(r.get(CATEGORY_ID)).name(r.get(CATEGORY_NAME)).build())
				.sorted(Comparator.comparing(Category::getName, Comparator.nullsLast(Comparator.naturalOrder())))
				.collect(Collectors.toList());
		log.info("Loading styles");
		this.categories.forEach(category -> {
			AggregateResults<String, String> styleResults = commands.aggregate(index,
					config.tag(CATEGORY_ID, category.getId()),
					AggregateOptions.builder().load(STYLE_NAME)
							.operation(Group.builder().property(STYLE_ID).property(STYLE_NAME)
									.reduce(CountDistinct.builder().property(PRODUCT_ID).as(COUNT).build()).build())
							.build());
			List<Style> styleList = styleResults.stream()
					.map(r -> Style.builder().id(r.get(STYLE_ID)).name(r.get(STYLE_NAME)).build())
					.sorted(Comparator.comparing(Style::getName, Comparator.nullsLast(Comparator.naturalOrder())))
					.collect(Collectors.toList());
			this.styles.put(category.getId(), styleList);
		});
	}

	private void loadBreweries() {
		RediSearchCommands<String, String> commands = connection.sync();
		try {
			Long length = commands.suglen(config.getProduct().getBrewery().getIndex());
			if (length != null && length > 0) {
				log.info("Found {} breweries - skipping load", length);
				return;
			}
		} catch (RedisCommandExecutionException e) {
			// ignore
		}
		log.info("Loading breweries");
		AggregateResults<String, String> results = commands.aggregate(config.getProduct().getIndex(), "*",
				AggregateOptions.builder().load(BREWERY_NAME).load(BREWERY_ICON)
						.operation(Group.builder().property(BREWERY_ID).property(BREWERY_NAME).property(BREWERY_ICON)
								.reduce(CountDistinct.builder().property(PRODUCT_ID).as(COUNT).build()).build())
						.build());
		ObjectMapper mapper = new ObjectMapper();
		results.forEach(r -> {
			BrewerySuggestionPayload payloadObject = new BrewerySuggestionPayload();
			payloadObject.setId(r.get(BREWERY_ID));
			payloadObject.setIcon(r.get(BREWERY_ICON));
			String payload = null;
			try {
				payload = mapper.writeValueAsString(payloadObject);
			} catch (JsonProcessingException e) {
				log.error("Could not serialize brewery payload {}", payloadObject, e);
			}
			String breweryName = r.get(BREWERY_NAME);
			if (breweryName == null) {
				return;
			}
			double count = Double.parseDouble(r.get(COUNT));
			commands.sugadd(config.getProduct().getBrewery().getIndex(), breweryName, count, payload);
		});
		log.info("Loaded {} breweries", results.size());
	}

	private void loadFoodPairings() throws IOException, URISyntaxException {
		RediSearchCommands<String, String> commands = connection.sync();
		commands.del(config.getProduct().getFoodPairings().getIndex());
		log.info("Loading food pairings");
		String index = config.getProduct().getIndex();
		AggregateResults<String, String> results = commands.aggregate(index, "*", AggregateOptions.builder()
				.operation(Group.builder().property(FOOD_PAIRINGS)
						.reduce(CountDistinct.builder().property(PRODUCT_ID).as(COUNT).build()).build())
				.operation(Sort.builder().property(SortProperty.builder().property(COUNT).order(Order.Desc).build())
						.build())
				.operation(Limit.builder().num(config.getProduct().getFoodPairings().getLimit()).build()).build());
		results.forEach(r -> {
			String foodPairings = r.get(FOOD_PAIRINGS);
			if (foodPairings == null || foodPairings.isEmpty() || foodPairings.isBlank()) {
				return;
			}
			Arrays.stream(foodPairings.split("[,\\n]")).map(s -> clean(s)).filter(s -> s.split(" ").length <= 2)
					.forEach(food -> {
						commands.sugadd(config.getProduct().getFoodPairings().getIndex(), food, 1.0, true);
					});
		});
		log.info("Loaded {} food pairings", results.size());
	}

	private String clean(String food) {
		List<String> allWords = Stream.of(food.toLowerCase().split(" "))
				.collect(Collectors.toCollection(ArrayList<String>::new));
		allWords.removeAll(stopwords);
		String result = allWords.stream().collect(Collectors.joining(" ")).trim();
		if (result.endsWith(".")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

}
