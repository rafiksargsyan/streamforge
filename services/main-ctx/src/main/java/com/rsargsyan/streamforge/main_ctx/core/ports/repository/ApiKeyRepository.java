package com.rsargsyan.streamforge.main_ctx.core.ports.repository;

import com.rsargsyan.streamforge.main_ctx.core.domain.aggregate.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {
  List<ApiKey> findByUserProfileId(Long userProfileId);
  Optional<ApiKey> findByIdAndUserProfileId(Long id, Long userProfileId);

  @Modifying
  @Query("UPDATE ApiKey k SET k.lastAccessTime = :now WHERE k.id = :id")
  void updateLastAccessTime(@Param("id") Long id, @Param("now") Instant now);
}
