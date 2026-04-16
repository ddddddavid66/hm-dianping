package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ScrollResult {
    // 返回结果的集合
    private List<?> list;
    // 最小 时间戳
    private Long minTime;
    // 偏移量
    private Integer offset;
}
