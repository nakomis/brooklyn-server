package brooklyn.rest.resources;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.catalog.CatalogItem;
import brooklyn.catalog.CatalogPredicates;
import brooklyn.entity.Entity;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.rest.api.CatalogApi;
import brooklyn.rest.domain.ApiError;
import brooklyn.rest.domain.CatalogEntitySummary;
import brooklyn.rest.domain.CatalogItemSummary;
import brooklyn.rest.domain.SummaryComparators;
import brooklyn.rest.transform.CatalogTransformer;
import brooklyn.rest.util.WebResourceUtils;
import brooklyn.util.ResourceUtils;
import brooklyn.util.text.StringPredicates;
import brooklyn.util.text.Strings;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.sun.jersey.core.header.FormDataContentDisposition;

public class CatalogResource extends AbstractBrooklynRestResource implements CatalogApi {

    private static final Logger log = LoggerFactory.getLogger(CatalogResource.class);
    
    @SuppressWarnings("rawtypes")
    private final Function<CatalogItem, CatalogItemSummary> TO_CATALOG_ITEM_SUMMARY = new Function<CatalogItem, CatalogItemSummary>() {
        @Override
        public CatalogItemSummary apply(@Nullable CatalogItem input) {
            return CatalogTransformer.catalogItemSummary(brooklyn(), input);
        }
    };

    @Override
    public Response createFromMultipart(InputStream uploadedInputStream, FormDataContentDisposition fileDetail) throws IOException {
      return create(CharStreams.toString(new InputStreamReader(uploadedInputStream, Charsets.UTF_8)));
    }

    @Override
    public Response create(String yaml) {
        CatalogItem<?,?> item;
        try {
            item = brooklyn().getCatalog().addItem(yaml);
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(ApiError.of(e))
                    .build();
        }
        String itemId = item.getId();
        log.info("REST created catalog item: "+item);
        
        // FIXME configurations/ not supported
        switch (item.getCatalogItemType()) {
        case TEMPLATE: return Response.created(URI.create("applications/" + itemId)).build();
        case ENTITY: return Response.created(URI.create("entities/" + itemId)).build();
        case POLICY: return Response.created(URI.create("policies/" + itemId)).build();
        case CONFIGURATION: return Response.created(URI.create("configurations/" + itemId)).build();
        default: throw new IllegalStateException("Unsupported catalog item type "+item.getCatalogItemType()+": "+item);
        }
    }

    @Override
    public List<CatalogItemSummary> listEntities(String regex, String fragment) {
        return getCatalogItemSummariesMatchingRegexFragment(CatalogPredicates.IS_ENTITY, regex, fragment);
    }

    @Override
    public List<CatalogItemSummary> listApplications(String regex, String fragment) {
        return getCatalogItemSummariesMatchingRegexFragment(CatalogPredicates.IS_TEMPLATE, regex, fragment);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CatalogEntitySummary getEntity(String entityId) {
      CatalogItem<?,?> result = brooklyn().getCatalog().getCatalogItem(entityId);
      if (result==null) {
        throw WebResourceUtils.notFound("Entity with id '%s' not found", entityId);
      }

      return CatalogTransformer.catalogEntitySummary(brooklyn(), (CatalogItem<? extends Entity,EntitySpec<?>>) result);
    }

    @Override
    public CatalogEntitySummary getApplication(String applicationId) {
        return getEntity(applicationId);
    }

    @Override
    public List<CatalogItemSummary> listPolicies(String regex, String fragment) {
        return getCatalogItemSummariesMatchingRegexFragment(CatalogPredicates.IS_POLICY, regex, fragment);
    }
    
    @Override
    public CatalogItemSummary getPolicy(String policyId) {
        CatalogItem<?,?> result = brooklyn().getCatalog().getCatalogItem(policyId);
        if (result==null) {
          throw WebResourceUtils.notFound("Policy with id '%s' not found", policyId);
        }

        return CatalogTransformer.catalogItemSummary(brooklyn(), result);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <T,SpecT> List<CatalogItemSummary> getCatalogItemSummariesMatchingRegexFragment(Predicate<CatalogItem<T,SpecT>> type, String regex, String fragment) {
        List filters = new ArrayList();
        filters.add(type);
        if (Strings.isNonEmpty(regex))
            filters.add(CatalogPredicates.xml(StringPredicates.containsRegex(regex)));
        if (Strings.isNonEmpty(fragment))
            filters.add(CatalogPredicates.xml(StringPredicates.containsLiteralCaseInsensitive(fragment)));

        return FluentIterable.from(brooklyn().getCatalog().getCatalogItems())
                .filter(Predicates.and(filters))
                .transform(TO_CATALOG_ITEM_SUMMARY)
                .toSortedList(SummaryComparators.idComparator());
    }

    @Override
    public Response getIcon(String itemId) {
        CatalogItem<?,?> result = brooklyn().getCatalog().getCatalogItem(itemId);
        String url = result.getIconUrl();
        if (url==null) {
            log.debug("No icon available for "+result+"; returning "+Status.NO_CONTENT);
            return Response.status(Status.NO_CONTENT).build();
        }
        
        if (brooklyn().isUrlServerSideAndSafe(url)) {
            // classpath URL's we will serve IF they end with a recognised image format;
            // paths (ie non-protocol) and 
            // NB, for security, file URL's are NOT served
            log.debug("Loading and returning "+url+" as icon for "+result);
            
            MediaType mime = WebResourceUtils.getImageMediaTypeFromExtension(Files.getFileExtension(url));
            Object content = ResourceUtils.create(brooklyn().getCatalog().getRootClassLoader()).getResourceFromUrl(url);
            return Response.ok(content, mime).build();
        }
        
        log.debug("Returning redirect to "+url+" as icon for "+result);
        
        // for anything else we do a redirect (e.g. http / https; perhaps ftp)
        return Response.temporaryRedirect(URI.create(url)).build();
    }

}