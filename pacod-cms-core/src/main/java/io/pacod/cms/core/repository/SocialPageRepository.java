package io.pacod.cms.core.repository;

import io.pacod.cms.core.data.SocialPage;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * {@code /social}'s one document, read by the public site through {@code SocialPageReader} and
 * written by the console's {@code MgmtSocialService}. Nothing here but the inherited methods: the
 * id is the constant {@link SocialPage#ID}.
 */
public interface SocialPageRepository extends MongoRepository<SocialPage, String> {
}
