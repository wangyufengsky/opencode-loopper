/** Shared synthetic transport across all real production routes; never a test UI. */
import type {Page} from '@playwright/test'
import {productFixture} from '../w2/productFixture'
import {w3ProductFixture} from '../w3/productFixture'
import {w4ProductFixture} from '../w4/productFixture'
import {w5ProductFixture} from '../w5/productFixture'
export async function allRoutesFixture(page:Page){
 const base=await productFixture(page),w3=await w3ProductFixture(page,true,base),w4=await w4ProductFixture(page,{base}),w5=await w5ProductFixture(page,{base})
 return {base,w3,w4,w5,errors:base.errors,unexpected:()=>[...base.unexpected,...w3.unexpected,...w3.ppt?.unexpected??[],...w4.unexpected,...w5.unexpected],writes:()=>[...base.requests,...w3.requests,...w3.ppt?.requests??[],...w4.requests,...w5.requests].filter(r=>r.method!=='GET')}
}
