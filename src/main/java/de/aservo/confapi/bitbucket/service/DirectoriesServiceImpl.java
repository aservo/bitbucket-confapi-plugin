package de.aservo.confapi.bitbucket.service;

import com.atlassian.crowd.embedded.api.CrowdDirectoryService;
import com.atlassian.crowd.embedded.api.Directory;
import com.atlassian.crowd.exception.DirectoryCurrentlySynchronisingException;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import de.aservo.confapi.bitbucket.model.util.DirectoryBeanUtil;
import de.aservo.confapi.commons.exception.InternalServerErrorException;
import de.aservo.confapi.commons.model.AbstractDirectoryBean;
import de.aservo.confapi.commons.model.DirectoriesBean;
import de.aservo.confapi.commons.model.DirectoryCrowdBean;
import de.aservo.confapi.commons.service.api.DirectoriesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static de.aservo.confapi.commons.util.BeanValidationUtil.processValidationResult;
import static java.lang.String.format;

@Named
@ExportAsService(DirectoriesService.class)
public class DirectoriesServiceImpl implements DirectoriesService {

    private static final Logger log = LoggerFactory.getLogger(DirectoriesServiceImpl.class);

    private final CrowdDirectoryService crowdDirectoryService;
    private final Validator validator;

    @Inject
    public DirectoriesServiceImpl(
            @ComponentImport CrowdDirectoryService crowdDirectoryService,
            @ComponentImport Validator validator) {
        this.crowdDirectoryService = checkNotNull(crowdDirectoryService);
        this.validator = validator;
    }

    @Override
    public DirectoriesBean getDirectories() {
        List<AbstractDirectoryBean> beans = new ArrayList<>();
        for (Directory directory : crowdDirectoryService.findAllDirectories()) {
            AbstractDirectoryBean crowdBean;
            crowdBean = DirectoryBeanUtil.toDirectoryBean(directory);
            beans.add(crowdBean);
        }
        return new DirectoriesBean(beans);
    }

    @Override
    public DirectoriesBean setDirectories(DirectoriesBean directoriesBean, boolean testConnection) {
        directoriesBean.getDirectories().forEach(directoryBaseBean -> {
            if (directoryBaseBean instanceof DirectoryCrowdBean) {

                //preps and validation
                DirectoryCrowdBean crowdBean = (DirectoryCrowdBean)directoryBaseBean;
                Directory directory = validateAndCreateDirectoryConfig(crowdBean, testConnection);

                //check if directory exists already and if yes, remove it
                Optional<Directory> presentDirectory = crowdDirectoryService.findAllDirectories().stream()
                        .filter(dir -> dir.getName().equals(directory.getName())).findFirst();
                if (presentDirectory.isPresent()) {
                    Directory presentDir = presentDirectory.get();
                    log.info("removing existing user directory configuration '{}' before adding new configuration '{}'", presentDir.getName(), directory.getName());
                    try {
                        crowdDirectoryService.removeDirectory(presentDir.getId());
                    } catch (DirectoryCurrentlySynchronisingException e) {
                        throw new InternalServerErrorException(e.getMessage());
                    }
                }

                //add new directory
                crowdDirectoryService.addDirectory(directory);
            } else {
                throw new InternalServerErrorException(format("Setting directory type '%s' is not supported (yet)", directoryBaseBean.getClass()));
            }
        });
        return getDirectories();
    }

    @Override
    public AbstractDirectoryBean addDirectory(AbstractDirectoryBean abstractDirectoryBean, boolean testConnection) {
        if (abstractDirectoryBean instanceof DirectoryCrowdBean) {
            DirectoryCrowdBean crowdBean = (DirectoryCrowdBean)abstractDirectoryBean;
            Directory directory = validateAndCreateDirectoryConfig(crowdBean, testConnection);
            Directory addedDirectory = crowdDirectoryService.addDirectory(directory);
            return DirectoryBeanUtil.toDirectoryBean(addedDirectory);
        } else {
            throw new InternalServerErrorException(format("Adding directory type '%s' is not supported (yet)", abstractDirectoryBean.getClass()));
        }
    }

    private Directory validateAndCreateDirectoryConfig(DirectoryCrowdBean crowdBean, boolean testConnection) {
        processValidationResult(validator.validate(crowdBean));
        Directory directory = DirectoryBeanUtil.toDirectory(crowdBean);
        String directoryName = crowdBean.getName();
        if (testConnection) {
            log.debug("testing user directory connection for {}", directoryName);
            crowdDirectoryService.testConnection(directory);
        }
        return directory;
    }

}