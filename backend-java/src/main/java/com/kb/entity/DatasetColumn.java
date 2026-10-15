package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 数据表的一列：物理列名 c1、c2…，display_name 为中文列名 */
@Data
@TableName("dataset_column")
public class DatasetColumn {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tableId;
    private Integer ordinal;
    private String physicalName;
    private String displayName;
    /** DATE / TIMESTAMP / BIGINT / DOUBLE / VARCHAR */
    private String dataType;
    private Integer isPercent;
    private String description;
    private String synonyms;
    /** 列画像 JSON：非空数、去重数、最小/最大值、常见取值 */
    private String profile;
}
