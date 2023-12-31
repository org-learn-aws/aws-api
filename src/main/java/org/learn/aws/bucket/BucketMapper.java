package org.learn.aws.bucket;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.util.List;

@Mapper(componentModel = "cdi")
public interface BucketMapper {

    @Mapping(target = "name", expression = "java(bucket.name())")
    @Mapping(target = "creationDate", expression = "java(bucket.creationDate())") BucketDto map(Bucket bucket);

    List<BucketDto> map(List<Bucket> buckets);

}
