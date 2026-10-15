package com.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 数据集中的一张表（一个数据块），物理表名 t_1、t_2… 存在该数据集的 DuckDB 文件里 */
@Data
@TableName("dataset_table")
public class DatasetTable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private Long blockId;
    private String sheetName;
    private String physicalName;
    private String displayName;
    private Integer headerRow;
    private Long rowCount;
    private String description;
    /** 逆透视等转换参数（JSON），NULL 表示原样 */
    private String transform;
}
