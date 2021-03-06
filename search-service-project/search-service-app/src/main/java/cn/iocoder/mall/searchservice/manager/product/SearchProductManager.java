package cn.iocoder.mall.searchservice.manager.product;

import cn.iocoder.common.framework.util.CollectionUtils;
import cn.iocoder.common.framework.vo.CommonResult;
import cn.iocoder.common.framework.vo.PageResult;
import cn.iocoder.mall.productservice.rpc.category.ProductCategoryRpc;
import cn.iocoder.mall.productservice.rpc.category.dto.ProductCategoryRespDTO;
import cn.iocoder.mall.productservice.rpc.sku.ProductSkuRpc;
import cn.iocoder.mall.productservice.rpc.sku.dto.ProductSkuListQueryReqDTO;
import cn.iocoder.mall.productservice.rpc.sku.dto.ProductSkuRespDTO;
import cn.iocoder.mall.productservice.rpc.spu.ProductSpuRpc;
import cn.iocoder.mall.productservice.rpc.spu.dto.ProductSpuRespDTO;
import cn.iocoder.mall.searchservice.convert.product.SearchProductConvert;
import cn.iocoder.mall.searchservice.rpc.product.dto.SearchProductConditionReqDTO;
import cn.iocoder.mall.searchservice.rpc.product.dto.SearchProductConditionRespDTO;
import cn.iocoder.mall.searchservice.rpc.product.dto.SearchProductPageReqDTO;
import cn.iocoder.mall.searchservice.rpc.product.dto.SearchProductRespDTO;
import cn.iocoder.mall.searchservice.service.product.SearchProductService;
import cn.iocoder.mall.searchservice.service.product.bo.SearchProductBO;
import cn.iocoder.mall.searchservice.service.product.bo.SearchProductConditionBO;
import cn.iocoder.mall.searchservice.service.product.bo.SearchProductSaveBO;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
public class SearchProductManager {

    private static final Integer REBUILD_FETCH_PER_SIZE = 100;

    @DubboReference(version = "${dubbo.consumer.ProductSpuRpc.version}")
    private ProductSpuRpc productSpuRpc;
    @DubboReference(version = "${dubbo.consumer.ProductSkuRpc.version}")
    private ProductSkuRpc productSkuRpc;
    @DubboReference(version = "${dubbo.consumer.ProductCategoryRpc.version}")
    private ProductCategoryRpc productCategoryRpc;

//    @DubboReference( version = "${dubbo.consumer.CartService.version}")
//    private CartService cartService;

    @Autowired
    private SearchProductService searchProductService;

    public PageResult<SearchProductRespDTO> pageSearchProduct(SearchProductPageReqDTO pageReqDTO) {
        PageResult<SearchProductBO> pageResult = searchProductService.pageSearchProduct(SearchProductConvert.INSTANCE.convert(pageReqDTO));
        return SearchProductConvert.INSTANCE.convertPage(pageResult);
    }

    public SearchProductConditionRespDTO getSearchProductCondition(SearchProductConditionReqDTO conditionReqDTO) {
        SearchProductConditionBO conditionBO =
                searchProductService.getSearchProductCondition(conditionReqDTO.getKeyword(), conditionReqDTO.getFields());
        return SearchProductConvert.INSTANCE.convert(conditionBO);
    }

    /**
     * ????????????????????? ES ??????
     *
     * @return ????????????
     */
    public Integer rebuild() {
        // TODO ??????????????????????????????????????????????????????????????????????????????
        Integer lastId = null;
        int rebuildCounts = 0;
        while (true) {
            // ????????????????????????????????????????????????
            CommonResult<List<Integer>> listProductSpuIdsResult = productSpuRpc.listProductSpuIds(lastId, REBUILD_FETCH_PER_SIZE);
            listProductSpuIdsResult.checkError();
            List<Integer> spuIds = listProductSpuIdsResult.getData();
            // ????????????????????? ES ???
            spuIds.forEach(this::saveProduct);
            // ???????????? lastId ???????????????
            rebuildCounts += listProductSpuIdsResult.getData().size();
            if (spuIds.size() < REBUILD_FETCH_PER_SIZE) {
                break;
            } else {
                lastId = spuIds.get(spuIds.size() - 1);
            }
        }
        // ????????????
        return rebuildCounts;
    }

    /**
     * ????????????????????? ES ??????
     *
     * @param id ?????? SPU ??????
     * @return ??????????????????
     */
    public Boolean saveProduct(Integer id) {
        // ???????????? SPU
        CommonResult<ProductSpuRespDTO> productSpuResult = productSpuRpc.getProductSpu(id);
        productSpuResult.checkError();
        if (productSpuResult.getData() == null) {
            log.error("[saveProduct][?????? SPU({}) ?????????]", id);
            return false;
        }
        // ???????????? SKU
        CommonResult<List<ProductSkuRespDTO>> listProductSkusResult =
                productSkuRpc.listProductSkus(new ProductSkuListQueryReqDTO().setProductSpuId(id));
        listProductSkusResult.checkError();
        if (CollectionUtils.isEmpty(listProductSkusResult.getData())) {
            log.error("[saveProduct][?????? SPU({}) ??? SKU ?????????]", id);
            return false;
        }
        // ??????????????????
        CommonResult<ProductCategoryRespDTO> getProductCategoryResult =
                productCategoryRpc.getProductCategory(productSpuResult.getData().getCid());
        getProductCategoryResult.checkError();
        if (getProductCategoryResult.getData() == null) {
            log.error("[saveProduct][?????? SPU({}) ?????????({}) ?????????]", id, productSpuResult.getData().getCid());
            return false;
        }
        // ??????????????? ES ???
        SearchProductSaveBO searchProductCreateBO = SearchProductConvert.INSTANCE.convert(
                productSpuResult.getData(), getProductCategoryResult.getData());
        ProductSkuRespDTO productSku = listProductSkusResult.getData().stream()
                .min(Comparator.comparing(ProductSkuRespDTO::getPrice)).orElse(null);
        assert productSku != null;
//        // ???????????? TODO ????????????????????????????????????????????????
//        CalcSkuPriceBO calSkuPriceResult  = cartService.calcSkuPrice(sku.getId());
        searchProductCreateBO.setOriginalPrice(productSku.getPrice());
        searchProductCreateBO.setBuyPrice(productSku.getPrice());
        searchProductCreateBO.setQuantity(productSku.getQuantity());
        searchProductService.saveSearchProduct(searchProductCreateBO);
        return true;
    }

}
