package com.hmdp.entity;

import lombok.Data;

import java.util.List;

@Data
public class Scroll {
    private Long minStamp;
    private List<?> list;
    private Integer pageSize;
    private Integer offset;
}
