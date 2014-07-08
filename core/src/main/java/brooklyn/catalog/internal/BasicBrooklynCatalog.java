package brooklyn.catalog.internal;

import brooklyn.util.guava.Maybe;
import io.brooklyn.camp.CampPlatform;
import io.brooklyn.camp.spi.AssemblyTemplate;
import io.brooklyn.camp.spi.instantiate.AssemblyTemplateInstantiator;
import io.brooklyn.camp.spi.pdp.DeploymentPlan;
import io.brooklyn.camp.spi.pdp.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.camp.brooklyn.api.AssemblyTemplateSpecInstantiator;
import brooklyn.catalog.BrooklynCatalog;
import brooklyn.catalog.CatalogItem;
import brooklyn.catalog.CatalogPredicates;
import brooklyn.config.BrooklynServerConfig;
import brooklyn.management.ManagementContext;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.javalang.AggregateClassLoader;
import brooklyn.util.javalang.LoadedClassLoader;
import brooklyn.util.javalang.Reflections;
import brooklyn.util.stream.Streams;
import brooklyn.util.text.Strings;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class BasicBrooklynCatalog implements BrooklynCatalog {

    private static final Logger log = LoggerFactory.getLogger(BasicBrooklynCatalog.class);
    
    private final ManagementContext mgmt;
    private final CatalogDo catalog;
    private volatile CatalogDo manualAdditionsCatalog;
    private volatile LoadedClassLoader manualAdditionsClasses;

    public BasicBrooklynCatalog(ManagementContext mgmt, String catalogUrl) {
        this(mgmt, CatalogDto.newDtoFromUrl(catalogUrl));
    }

    public BasicBrooklynCatalog(final ManagementContext mgmt, final CatalogDto dto) {
        this.mgmt = Preconditions.checkNotNull(mgmt, "managementContext");
        this.catalog = new CatalogDo(mgmt, dto);
    }

    public boolean blockIfNotLoaded(Duration timeout) {
        try {
            return getCatalog().blockIfNotLoaded(timeout);
        } catch (Exception e) {
            throw Exceptions.propagate(e);
        }
    }
    
    public CatalogDo getCatalog() {
        return catalog;
    }

    protected CatalogItemDo<?,?> getCatalogItemDo(String id) {
        return catalog.getCache().get(id);
    }
    
    @Override
    public CatalogItem<?,?> getCatalogItem(String id) {
        if (id==null) return null;
        CatalogItemDo<?,?> itemDo = getCatalogItemDo(id);
        if (itemDo==null) return null;
        return itemDo.getDto();
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public <T,SpecT> CatalogItem<T,SpecT> getCatalogItem(Class<T> type, String id) {
        if (id==null) return null;
        CatalogItem<?,?> result = getCatalogItem(id);
        if (type==null || type.isAssignableFrom(result.getCatalogItemJavaType())) 
            return (CatalogItem<T,SpecT>)result;
        return null;
    }
    
    public ClassLoader getRootClassLoader() {
        return catalog.getRootClassLoader();
    }

    /**
     * Loads this catalog
     */
    public void load() {
        log.debug("Loading catalog for " + mgmt);
        getCatalog().load(mgmt, null);
        if (log.isDebugEnabled()) {
            log.debug("Loaded catalog for " + mgmt + ": " + catalog + "; search classpath is " + catalog.getRootClassLoader());
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T, SpecT> SpecT createSpec(CatalogItem<T, SpecT> item) {
        CatalogItemDo<T,SpecT> loadedItem = (CatalogItemDo<T, SpecT>) getCatalogItemDo(item.getId());
        
        Class<SpecT> specType = loadedItem.getSpecType();
        if (specType==null) return null;
            
        String yaml = loadedItem.getPlanYaml();
        SpecT spec = null;
            
        if (yaml!=null) {
            DeploymentPlan plan = makePlanFromYaml(yaml);
            CampPlatform camp = BrooklynServerConfig.getCampPlatform(mgmt).get();
            
            // TODO should not register new AT each time we instantiate from the same plan; use some kind of cache
            AssemblyTemplate at = camp.pdp().registerDeploymentPlan(plan);
            
            try {
                AssemblyTemplateInstantiator instantiator = at.getInstantiator().newInstance();
                if (instantiator instanceof AssemblyTemplateSpecInstantiator) {
                    return (SpecT) ((AssemblyTemplateSpecInstantiator)instantiator).createSpec(at, camp);
                }
                throw new IllegalStateException("Unable to instantiate YAML; incompatible instantiator "+instantiator+" for "+at);
            } catch (Exception e) {
                throw Exceptions.propagate(e);
            }
        }
            
        // revert to legacy mechanism
        try {
            if (loadedItem.getJavaType()!=null) {
                SpecT specT = (SpecT) Reflections.findMethod(specType, "create", Class.class).invoke(null, loadedItem.loadJavaClass(mgmt));
                spec = specT;
            }
        } catch (Exception e) {
            Exceptions.propagateIfFatal(e);
            throw new IllegalStateException("Unsupported creation of spec type "+specType+"; it must have a public static create(Class) method", e);
        }

        if (spec==null) 
            throw new IllegalStateException("Unknown how to create instance of "+this);

        return spec;
    }
    
    @SuppressWarnings("unchecked")
    @Override
    /** @deprecated since 0.7.0 use {@link #createSpec(CatalogItem)} */
    @Deprecated
    public <T,SpecT> Class<? extends T> loadClass(CatalogItem<T,SpecT> item) {
        if (log.isDebugEnabled())
            log.debug("Loading class for catalog item " + item);
        Preconditions.checkNotNull(item);
        CatalogItemDo<?,?> loadedItem = getCatalogItemDo(item.getId());
        if (loadedItem==null) throw new NoSuchElementException("Unable to load '"+item.getId()+"' to instantiate it");
        return (Class<? extends T>) loadedItem.getJavaClass();
    }
    
    @SuppressWarnings("unchecked")
    @Override
    /** @deprecated since 0.7.0 use {@link #createSpec(CatalogItem)} */
    @Deprecated
    public <T> Class<? extends T> loadClassByType(String typeName, Class<T> typeClass) {
        Iterable<CatalogItem<Object,Object>> resultL = getCatalogItems(CatalogPredicates.javaType(Predicates.equalTo(typeName)));
        if (Iterables.isEmpty(resultL)) throw new NoSuchElementException("Unable to find catalog item for type "+typeName);
        CatalogItem<?,?> resultI = resultL.iterator().next();
        if (log.isDebugEnabled() && Iterables.size(resultL)>1) {
            log.debug("Found "+Iterables.size(resultL)+" matches in catalog for type "+typeName+"; returning the first, "+resultI);
        }
        return (Class<? extends T>) loadClass(resultI);
    }

    @Deprecated /** @deprecated since 0.7.0 only used by other deprecated items */ 
    private <T,SpecT> CatalogItemDtoAbstract<T,SpecT> getAbstractCatalogItem(CatalogItem<T,SpecT> item) {
        while (item instanceof CatalogItemDo) item = ((CatalogItemDo<T,SpecT>)item).itemDto;
        if (item==null) return null;
        if (item instanceof CatalogItemDtoAbstract) return (CatalogItemDtoAbstract<T,SpecT>) item;
        throw new IllegalStateException("Cannot unwrap catalog item '"+item+"' (type "+item.getClass()+") to restore DTO");
    }

    private CatalogItemDtoAbstract<?,?> getAbstractCatalogItem(String yaml) {
        DeploymentPlan plan = makePlanFromYaml(yaml);
        
        String name = null;
        CatalogLibrariesDto libraries = null;

        Maybe<Map> possibleCatalog = plan.getCustomAttribute("brooklyn.catalog", Map.class);
        if (possibleCatalog.isPresent()) {
            Map catalog = possibleCatalog.get();
            Map<String, Object> cast = (Map<String, Object>) possibleCatalog.get();
            if (catalog.containsKey("name") && catalog.get("name") != null) {
                name = String.valueOf(catalog.get("name"));
            }
            Object possibleLibraries = catalog.get("libraries");
            if (possibleLibraries != null) {
                if (possibleLibraries instanceof List) {
                    libraries = CatalogLibrariesDto.fromList((List<?>) possibleLibraries);
                }
            }
        }

        // TODO #3 support version info

        // take name from plan if not specified in brooklyn.catalog section not supplied
        if (Strings.isBlank(name)) {
            name = plan.getName();
            if (Strings.isBlank(name)) {
                if (plan.getServices().size()==1) {
                    Service svc = Iterables.getOnlyElement(plan.getServices());
                    name = svc.getServiceType();
                }
            }
        }
        
        // build the catalog item from the plan (as CatalogItem<Entity> for now)
        // TODO applications / templates
        // TODO long-term support policies etc
        
        return CatalogItems.newEntityFromPlan(name, libraries, plan, yaml);
    }

    private DeploymentPlan makePlanFromYaml(String yaml) {
        CampPlatform camp = BrooklynServerConfig.getCampPlatform(mgmt).get();
        return camp.pdp().parseDeploymentPlan(Streams.newReaderWithContents(yaml));
    }

    @Override
    public CatalogItem<?,?> addItem(String yaml) {
        log.debug("Adding manual catalog item to "+mgmt+": "+yaml);
        Preconditions.checkNotNull(yaml, "yaml");
        if (manualAdditionsCatalog==null) loadManualAdditionsCatalog();
        CatalogItemDtoAbstract<?,?> itemDto = getAbstractCatalogItem(yaml);
        manualAdditionsCatalog.addEntry(itemDto);
        return itemDto;
    }

    @Override @Deprecated /** @deprecated see super */
    public void addItem(CatalogItem<?,?> item) {
        log.debug("Adding manual catalog item to "+mgmt+": "+item);
        Preconditions.checkNotNull(item, "item");
        if (manualAdditionsCatalog==null) loadManualAdditionsCatalog();
        manualAdditionsCatalog.addEntry(getAbstractCatalogItem(item));
    }

    @Override @Deprecated /** @deprecated see super */
    public CatalogItem<?,?> addItem(Class<?> type) {
        log.debug("Adding manual catalog item to "+mgmt+": "+type);
        Preconditions.checkNotNull(type, "type");
        if (manualAdditionsCatalog==null) loadManualAdditionsCatalog();
        manualAdditionsClasses.addClass(type);
        return manualAdditionsCatalog.classpath.addCatalogEntry(type);
    }

    private synchronized void loadManualAdditionsCatalog() {
        if (manualAdditionsCatalog!=null) return;
        CatalogDto manualAdditionsCatalogDto = CatalogDto.newNamedInstance(
                "Manual Catalog Additions", "User-additions to the catalog while Brooklyn is running, " +
        		"created "+Time.makeDateString());
        CatalogDo manualAdditionsCatalog = catalog.addCatalog(manualAdditionsCatalogDto);
        if (manualAdditionsCatalog==null) {
            // not hard to support, but slightly messy -- probably have to use ID's to retrieve the loaded instance
            // for now block once, then retry
            log.warn("Blocking until catalog is loaded before changing it");
            boolean loaded = blockIfNotLoaded(Duration.TEN_SECONDS);
            if (!loaded)
                log.warn("Catalog still not loaded after delay; subsequent operations may fail");
            manualAdditionsCatalog = catalog.addCatalog(manualAdditionsCatalogDto);
            if (manualAdditionsCatalog==null) {
                throw new UnsupportedOperationException("Catalogs cannot be added until the base catalog is loaded, and catalog is taking a while to load!");
            }
        }
        
        log.debug("Creating manual additions catalog for "+mgmt+": "+manualAdditionsCatalog);
        manualAdditionsClasses = new LoadedClassLoader();
        ((AggregateClassLoader)manualAdditionsCatalog.classpath.getLocalClassLoader()).addFirst(manualAdditionsClasses);
        
        // expose when we're all done
        this.manualAdditionsCatalog = manualAdditionsCatalog;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <T,SpecT> Iterable<CatalogItem<T,SpecT>> getCatalogItems() {
        return ImmutableList.copyOf((Iterable)catalog.getCache().values());
    }
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public <T,SpecT> Iterable<CatalogItem<T,SpecT>> getCatalogItems(Predicate<? super CatalogItem<T,SpecT>> filter) {
        Iterable<CatalogItemDo<T,SpecT>> filtered = Iterables.filter((Iterable)catalog.getCache().values(), (Predicate<CatalogItem<T,SpecT>>)(Predicate) filter);
        return Iterables.transform(filtered, BasicBrooklynCatalog.<T,SpecT>itemDoToDto());
    }

    private static <T,SpecT> Function<CatalogItemDo<T,SpecT>, CatalogItem<T,SpecT>> itemDoToDto() {
        return new Function<CatalogItemDo<T,SpecT>, CatalogItem<T,SpecT>>() {
            @Override
            public CatalogItem<T,SpecT> apply(@Nullable CatalogItemDo<T,SpecT> item) {
                return item.getDto();
            }
        };
    }

    transient CatalogXmlSerializer serializer;
    
    public String toXmlString() {
        if (serializer==null) loadSerializer();
        return serializer.toString(catalog.dto);
    }
    
    private synchronized void loadSerializer() {
        if (serializer==null) 
            serializer = new CatalogXmlSerializer();
    }

}