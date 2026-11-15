import { ShopApi } from '../../../api'
import { defaultShopPrice, toCartUnit } from '../../../shared/cart/cartStorage'
import { useApiData } from '../../../shared/hooks/useApiData'
import type { ItemType, UnitType } from '../../../types/kitchen'

/** One thing the shop sells: a catalog ingredient or piece of equipment, with its demo price. */
export interface ShopCatalogItem {
  id: number
  name: string
  itemType: ItemType
  description?: string
  basePrice: number
  defaultUnit?: UnitType
  imageUrl?: string | null
}

/** Units an ingredient can be bought in; equipment is always counted. */
export const INGREDIENT_UNIT_OPTIONS: UnitType[] = ['KG', 'GRAM', 'LITER', 'ML', 'COUNT']
export const EQUIPMENT_UNIT_OPTIONS: UnitType[] = ['COUNT']

const loadCatalog = async (): Promise<ShopCatalogItem[]> => {
  const [ingredients, equipments] = await Promise.all([ShopApi.getIngredients(), ShopApi.getEquipment()])
  const ingredientItems: ShopCatalogItem[] = Array.isArray(ingredients)
    ? ingredients.map((item) => ({
        id: item.id,
        name: item.name,
        itemType: 'INGREDIENT',
        description: item.description || 'High quality ingredient',
        basePrice: defaultShopPrice('INGREDIENT', item.basePrice),
        defaultUnit: toCartUnit(item.defaultUnit),
        imageUrl: item.imageUrl,
      }))
    : []
  const equipmentItems: ShopCatalogItem[] = Array.isArray(equipments)
    ? equipments.map((item) => ({
        id: item.id,
        name: item.name,
        itemType: 'EQUIPMENT',
        description: item.description || 'Professional kitchen equipment',
        basePrice: defaultShopPrice('EQUIPMENT', item.basePrice),
        defaultUnit: 'COUNT',
        imageUrl: item.imageUrl,
      }))
    : []
  return [...ingredientItems, ...equipmentItems]
}

const NO_ITEMS: ShopCatalogItem[] = []

/** Everything the shop sells (ingredients, then equipment), loaded once. */
export function useShopCatalog() {
  const catalog = useApiData('shop-catalog', loadCatalog)
  return { items: catalog.data ?? NO_ITEMS, loading: catalog.loading, error: catalog.error ?? '', reload: catalog.reload }
}
