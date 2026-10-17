package io.pacod.cms.site.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ApplicationHealthService {

    public ApplicationStatus health() {
        ApplicationStatus status = ApplicationStatus.OK;
        return status;
    }

}
