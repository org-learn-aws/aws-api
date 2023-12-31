package org.learn.aws.mediaPipeline;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MediaPipelineSummaryDto {
    private String id;
    private String arn;
    private String type;
}
