/**
 * 
 */
package au.edu.alveo.uima;

import au.edu.alveo.client.entity.AlveoException;
import au.edu.alveo.uima.conversions.FallingBackUIMAAlveoConverter;
import au.edu.alveo.uima.conversions.UIMAToAlveoAnnConverter;
import au.edu.alveo.client.RestClient;
import au.edu.alveo.client.entity.EntityNotFoundException;
import au.edu.alveo.client.entity.InvalidServerAddressException;
import au.edu.alveo.client.entity.Item;
import au.edu.alveo.client.entity.ItemList;
import au.edu.alveo.client.entity.UnauthorizedAPIKeyException;
import org.apache.uima.UimaContext;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASException;
import org.apache.uima.collection.CollectionException;
import org.apache.uima.collection.CollectionReaderDescription;
import org.apache.uima.fit.component.CasCollectionReader_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.descriptor.TypeCapability;
import org.apache.uima.fit.factory.CollectionReaderFactory;
import org.apache.uima.fit.factory.ConfigurationParameterFactory;
import org.apache.uima.fit.factory.TypeSystemDescriptionFactory;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.util.Progress;
import org.apache.uima.util.ProgressImpl;
import org.openrdf.OpenRDFException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.apache.uima.fit.factory.ConfigurationParameterFactory.ConfigurationData;

/**
 * A collection reader which automatically converts Alveo annotations into UIMA data structures
 * so that they can be used by other UIMA processing components.
 * 
 */
@TypeCapability(outputs = { 
		"au.edu.alveo.uima.types.AlveoItemSource",
		"au.edu.alveo.uima.types.VLabDocSource",
		"au.edu.alveo.uima.types.UnknownItemAnnotation",
		"au.edu.alveo.uima.types.GeneratedItemAnnotation",
		"au.edu.alveo.uima.types.ItemMetadata"
})
public class ItemListCollectionReader extends CasCollectionReader_ImplBase {
	private static final Logger LOG = LoggerFactory.getLogger(ItemListCollectionReader.class);

	public static final String PARAM_ALVEO_BASE_URL = "alveoBaseUrl";
	public static final String PARAM_ALVEO_ITEM_LIST_ID = "itemListId";
	public static final String PARAM_ALVEO_API_KEY = "alveoApiKey";
	public static final String PARAM_INCLUDE_RAW_DOCS = "includeRawDocs";
	public static final String PARAM_INCLUDE_ANNOTATIONS = "includeAnnotations";
	public static final String PARAM_ANNOTATION_CONVERTERS = "annotationConverters";

	@ConfigurationParameter(name = PARAM_ALVEO_ITEM_LIST_ID, mandatory = true, description = "Item ID which should be retrieved and converted into a "
			+ "set of UIMA CAS documents")
	private String itemListId;

	@ConfigurationParameter(name = PARAM_ALVEO_BASE_URL, mandatory = true,
			description = "Base URL for the Alveo REST/JSON API server "
			+ "- eg http://vlab.example.org/ ; the URL for the item list "
			+ " will be constructed by appending 'item_lists/{item_list_id}.json' to this URL")
	private URL baseUrl;

	@ConfigurationParameter(name = PARAM_ALVEO_API_KEY, mandatory = true, description = "API key for the vLab account (available from the web interface")
	private String apiKey;

	@ConfigurationParameter(name = PARAM_INCLUDE_RAW_DOCS, mandatory = false, description = "Include raw document sources as separate SofAs")
	private boolean includeRawDocs = false;
	
	@ConfigurationParameter(name = PARAM_INCLUDE_ANNOTATIONS, mandatory = false, description = "Include textual annotations when they are present")
	private boolean includeAnnotations = true;

	@ConfigurationParameter(name = PARAM_ANNOTATION_CONVERTERS, mandatory = false,
			description = "Classes for converting UIMA annotations into Alveo annotations in preference to" +
					"the default strategy of looking for label or annotation type features with appropriate names " +
					"or guessing the annotation type URI based on the source annotation type. All classes " +
					"named here must implement au.edu.alveo.uima.conversions.UIMAToAlveoAnnConverter")
	private String[] annotationConverterClasses = new String[] {};


	private ItemList itemList;
	private Iterator<? extends Item> itemsIter;
	private int itemsFetched;
	private int totalItems;
	private ItemCASAdapter itemCASAdapter;
	private UIMAToAlveoAnnConverter converter;


	/** Create a collection reader description corresponding to the provided configuration data.
	 *
	 * This method should be used in preference to the standard instantiation methods because
	 * the type system needs to be determined dynamically by querying the Alveo server for
	 * known annotation types, and this factory method takes care of that.
	 *
	 * @param confData Any configuration data values in the standard UIMAfit format (interleaved keys and values)
	 *                 as could be used in a call to
	 *                 {@link org.apache.uima.fit.factory.ConfigurationParameterFactory#createConfigurationData(java.lang.Object...)}
	 * @return a new <code>CollectionReaderDescription</code> instance suitable for using a pipeline directly
	 *    or serializing to disk as XML
	 * @throws ResourceInitializationException
	 */
	public static CollectionReaderDescription createDescription(Object... confData) throws ResourceInitializationException {
		return createDescription(TypeSystemDescriptionFactory.createTypeSystemDescription(), confData);
	}

	/** Create a collection reader description corresponding to the provided configuration data.
	 *
	 * Similar to {@link #createDescription(java.lang.Object...)} except it will explicitly merge with
	 * the supplied type system instead of generating one using UIMAfit
	 *
	 * @param externalTypeSystem An externally create type system which should be merged with the dynamic type
	 *                           system from the server
	 * @param confData Any configuration data values in the standard UIMAfit format
	 * @return a new <code>CollectionReaderDescription</code> instance suitable for using a pipeline directly
	 *    or serializing to disk as XML
	 * @throws ResourceInitializationException
	 */
	public static CollectionReaderDescription createDescription(TypeSystemDescription externalTypeSystem, Object... confData)
			throws ResourceInitializationException {
		ConfigurationData confDataParsed = ConfigurationParameterFactory.createConfigurationData(confData);
		String vlabUrl = null, vlabApiKey = null;
		// since we don't yet have a reader, we need to semi-manually parse the params
		for (int i = 0; i < confDataParsed.configurationParameters.length; i++) {
			String paramName = confDataParsed.configurationParameters[i].getName();
			Object value = confDataParsed.configurationValues[i];
			if (paramName.equals(PARAM_ALVEO_API_KEY))
				vlabApiKey = (String) value;
			else if (paramName.equals(PARAM_ALVEO_BASE_URL))
				vlabUrl = (String) value;
		}
		if (vlabApiKey == null || vlabUrl == null)
			throw new ResourceInitializationException(ResourceInitializationException.CONFIG_SETTING_ABSENT,
					new Object[] {PARAM_ALVEO_API_KEY + ", " + PARAM_ALVEO_BASE_URL + ", " + PARAM_ALVEO_ITEM_LIST_ID});
		TypeSystemDescription tsd;
		try {
			tsd = ItemListCollectionReader.getTypeSystemDescription(vlabUrl, vlabApiKey, externalTypeSystem);
		} catch (Exception e) {
			throw new ResourceInitializationException(e);
		}
		return CollectionReaderFactory.createReaderDescription(ItemListCollectionReader.class,
				tsd, confData);
	}

	protected static TypeSystemDescription getTypeSystemDescription(String vlabUrl, String vlabApiKey,
			TypeSystemDescription extTypeSystem)
			throws UnauthorizedAPIKeyException, EntityNotFoundException,
			InvalidServerAddressException, ResourceInitializationException, URISyntaxException, OpenRDFException {
		RestClient client = new RestClient(vlabUrl, vlabApiKey);
		TypeSystemAutoAugmenter tsag = new TypeSystemAutoAugmenter(client, extTypeSystem);
		for (String corpusName : getCorpusNames())
			tsag.addCorpus(corpusName);
		return tsag.getTypeSystemDescription();
	}

	/** Get a list of known collections (corpora) */
	public static Collection<String> getCorpusNames() {
		// XXX: horrible hack.
		// this should be calling a REST API method,
		// but currently this doesn't exist.
		// TODO: Once https://track.intersect.org.au/browse/HCSVLAB-868
		// is fixed, this should be replaced with a call to that method.
		// Note that we can't even set this in the UIMA descriptor, since
		// we want to know these before the reader has been instantiatied.
		// If extra collections are listed but are not found or
		// have insufficient permissions, they will be ignored with a
		// logged warning.
		String[] corpusNames = new String[] {
				"ace",
				"art",
				"austalk",
				"austlit",
				"avozes",
				"braidedchannels",
				"cooee",
				"gcsause",
				"ice",
				"jakartan_indonesian",
				"mbep",
				"mitcheldelbridge",
				"monash",
				"paradisec",
				"pixar",
				"rirusyd"
		};
		return Arrays.asList(corpusNames);
	}

	@Override
	public void initialize(UimaContext context) throws ResourceInitializationException {
		try {
			List<UIMAToAlveoAnnConverter> componentConverters = new ArrayList<UIMAToAlveoAnnConverter>(annotationConverterClasses.length + 1);
			for (String accName : annotationConverterClasses)
				componentConverters.add(getConverterInstance(accName));
			converter = FallingBackUIMAAlveoConverter.withDefault(componentConverters);
			fetchItemList();
		} catch (AlveoException e) {
			throw new ResourceInitializationException(e);
		} catch (ClassNotFoundException e) {
			throw new ResourceInitializationException(e);
		} catch (InstantiationException e) {
			throw new ResourceInitializationException(e);
		} catch (IllegalAccessException e) {
			throw new ResourceInitializationException(e);
		}
	}


	private UIMAToAlveoAnnConverter getConverterInstance(String className)
			throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		Class<?> convClass = Class.forName(className);
		return (UIMAToAlveoAnnConverter) convClass.newInstance();
	}

	private void fetchItemList() throws AlveoException {
		RestClient client = new RestClient(baseUrl.toString(), apiKey);
		try {
			itemList = client.getItemList(itemListId);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		itemsIter = itemList.getCatalogItems().listIterator();
		itemsFetched = 0;
		totalItems = itemList.numItems();
		itemCASAdapter = new ItemCASAdapter(baseUrl.toString(), includeRawDocs, includeAnnotations,
				converter);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.uima.collection.CollectionReader#getNext(org.apache.uima.cas
	 * .CAS)
	 */
	public void getNext(CAS cas) throws IOException, CollectionException {
		++itemsFetched;
		try {
			itemCASAdapter.storeItemInCas(itemsIter.next(), cas);
		} catch (CASException e) {
			throw new CollectionException(e);
		}
	}



	/*
	 * (non-Javadoc)
	 * 
	 * @see org.apache.uima.collection.base_cpm.BaseCollectionReader#hasNext()
	 */
	public boolean hasNext() throws IOException, CollectionException {
		return itemsIter.hasNext();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.apache.uima.collection.base_cpm.BaseCollectionReader#getProgress()
	 */
	public Progress[] getProgress() {
		return new Progress[] { new ProgressImpl(itemsFetched, totalItems, Progress.ENTITIES) };
	}

}
